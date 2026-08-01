//! Native backend for `LiveSubtitleService` (real-time captions for device audio).
//!
//! Audio arrives via `pushAudio` in small blocks (~64 ms). We build up a
//! *segment* of speech, send the whole segment to a worker thread for
//! transcription roughly once per tick, and display the result as a *partial*
//! (replaceable) caption. When trailing silence is detected — or the segment
//! hits a hard cap — the segment is *finalized*: transcribed once more and
//! committed, then the buffer starts fresh.
//!
//! Three things keep latency low and bounded:
//! - Partial jobs are only submitted while the worker is idle (latest-wins),
//!   so a slow device can never build up a queue and drift behind real time.
//! - Final jobs are always queued (FIFO), so committed text is never lost.
//! - When finals queue up faster than they are processed, the worker folds
//!   them into one batched run: fixed-window models (Whisper) cost nearly
//!   the same per run regardless of audio length, so batching is what lets
//!   a slow device catch up instead of dropping text.

use crossbeam_channel;
use jni::objects::{JClass, JObject};
use jni::JNIEnv;
use once_cell::sync::Lazy;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

use crate::engine;

const SAMPLE_RATE: usize = 16_000;
/// Minimum audio between two partial-hypothesis updates (~0.7 s).
const TICK_SAMPLES: usize = 11_200;
/// Trailing silence that finalizes the current segment (~0.7 s).
const FINALIZE_SILENCE_SAMPLES: usize = 11_200;
/// Hard cap on a single segment. Partials re-transcribe the whole current
/// segment, so this also caps the per-job inference cost: the worst-case
/// caption offset is roughly one queued final plus one in-flight job, both
/// proportional to this — keep it short so captions stay close to real time
/// even in dense speech.
const MAX_SEGMENT_SAMPLES: usize = 6 * SAMPLE_RATE;
/// A queued *final* older than this is dropped (a "…" gap is shown instead).
/// This bounds the audio-to-caption offset on devices that can't transcribe
/// in real time — without it the finals queue grows and the captions drift
/// further behind the longer the audio plays. Merging (below) makes this a
/// last resort: it only fires when even batched runs can't keep up.
const MAX_FINAL_LAG_SAMPLES: u64 = (8 * SAMPLE_RATE) as u64;
/// Cap on one merged final run. Models with a fixed input window (Whisper
/// pads everything to 30 s) cost nearly the same per run no matter how short
/// the audio is, so transcribing queued finals one by one wastes most of the
/// run on padding. Folding everything that is already queued into one run
/// amortizes that fixed cost — this is what lets a device that can't keep up
/// segment-by-segment still catch up without dropping text. Kept under the
/// 30 s window so a merged run is still a single model pass.
const MAX_MERGED_SAMPLES: usize = 25 * SAMPLE_RATE;
/// A queued *partial* older than this is stale; skip it (a fresh one follows).
const MAX_PARTIAL_LAG_SAMPLES: u64 = (3 * SAMPLE_RATE) as u64;
/// A partial is only submitted when its predicted transcription time is at
/// most this. Partials are cosmetic — on a slow (or thermally throttled)
/// device the worker's time is better spent on finals, which are what keep
/// the transcript moving. Finals are never gated on cost.
const MAX_PARTIAL_COST_SECS: f32 = 2.0;
/// Audio kept while waiting for speech so the first word isn't clipped (0.4 s).
const PREROLL_SAMPLES: usize = 6_400;
/// Silence kept after the last speech when finalizing on silence (0.2 s).
const FINAL_TAIL_SAMPLES: usize = 3_200;
/// Per-block RMS at or above this counts as sound worth transcribing.
const SPEECH_RMS: f32 = 0.004;
/// Segments shorter than this are dropped as noise (0.25 s).
const MIN_SEGMENT_SAMPLES: usize = 4_000;

struct Job {
    samples: Vec<f32>,
    is_final: bool,
    /// Position of the job's last sample in the overall pushed-audio stream,
    /// used by the worker to measure how stale the job is.
    end_sample: u64,
}

struct LiveSubtitleState {
    /// Current un-finalized speech segment.
    segment: Vec<f32>,
    /// Rolling pre-speech audio, prepended once speech starts.
    preroll: Vec<f32>,
    has_speech: bool,
    /// Consecutive quiet samples at the tail of `segment`.
    silence_run: usize,
    samples_since_tick: usize,
    worker_tx: crossbeam_channel::Sender<Job>,
    worker_busy: Arc<AtomicBool>,
    /// Total samples ever pushed; shared with the worker for lag measurement.
    total_pushed: Arc<AtomicU64>,
    /// Number of final jobs queued but not yet fully processed. While > 0,
    /// partials are not submitted so the worker catches up on finals first.
    pending_finals: Arc<AtomicUsize>,
    /// Measured transcription speed as milli-RTF (compute ms per audio ms),
    /// smoothed by the worker; 0 until the first job completes. Used to
    /// predict a partial's cost before submitting it.
    rtf_milli: Arc<AtomicU32>,
}

static LIVE_STATE: Lazy<Mutex<Option<LiveSubtitleState>>> = Lazy::new(|| Mutex::new(None));

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_LiveSubtitleService_initNative(
    env: JNIEnv,
    _class: JClass,
    service: JObject,
) {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info),
    );
    let vm = match env.get_java_vm() {
        Ok(vm) => Arc::new(vm),
        Err(_) => return,
    };
    let service_ref = match env.new_global_ref(&service) {
        Ok(r) => r,
        Err(_) => return,
    };

    let (tx, rx) = crossbeam_channel::unbounded::<Job>();
    let worker_busy = Arc::new(AtomicBool::new(false));
    let total_pushed = Arc::new(AtomicU64::new(0));
    let pending_finals = Arc::new(AtomicUsize::new(0));
    let rtf_milli = Arc::new(AtomicU32::new(0));

    *LIVE_STATE.lock().unwrap() = Some(LiveSubtitleState {
        segment: Vec::new(),
        preroll: Vec::new(),
        has_speech: false,
        silence_run: 0,
        samples_since_tick: 0,
        worker_tx: tx,
        worker_busy: worker_busy.clone(),
        total_pushed: total_pushed.clone(),
        pending_finals: pending_finals.clone(),
        rtf_milli: rtf_milli.clone(),
    });

    std::thread::spawn(move || {
        let mut env = match vm.attach_current_thread() {
            Ok(e) => e,
            Err(e) => {
                log::error!("Subtitle worker failed to attach: {}", e);
                return;
            }
        };
        let service_obj = service_ref.as_obj();
        // Whether the previously processed final was dropped for lag — used
        // to emit a single "…" gap marker per run of dropped finals.
        let mut gap_pending = false;

        let deliver = |env: &mut jni::JNIEnv, text: &str, is_final: bool| {
            if let Ok(jtxt) = env.new_string(text) {
                let _ = env.call_method(
                    service_obj,
                    "onSubtitleText",
                    "(Ljava/lang/String;Z)V",
                    &[(&jtxt).into(), is_final.into()],
                );
            }
        };

        while let Ok(job) = rx.recv() {
            let mut job = job;
            // Fold queued finals into this run (see MAX_MERGED_SAMPLES). A
            // queued partial is dropped instead: it re-transcribes audio a
            // queued final already covers, and a fresh one follows anyway.
            if job.is_final {
                let mut merged = 0usize;
                while job.samples.len() < MAX_MERGED_SAMPLES {
                    match rx.try_recv() {
                        Ok(next) => {
                            if next.is_final {
                                job.samples.extend_from_slice(&next.samples);
                                job.end_sample = next.end_sample;
                                pending_finals.fetch_sub(1, Ordering::SeqCst);
                                merged += 1;
                            }
                        }
                        Err(_) => break,
                    }
                }
                if merged > 0 {
                    log::info!(
                        "Merged {} queued finals into one {:.1}s run",
                        merged + 1,
                        job.samples.len() as f64 / SAMPLE_RATE as f64
                    );
                }
            }

            // Stale-job policy: if transcription can't keep up with the
            // audio, skip old work instead of drifting ever further behind.
            // A merged final ends at the newest queued audio, so merging
            // usually keeps the lag below the drop threshold by itself.
            let lag = total_pushed
                .load(Ordering::SeqCst)
                .saturating_sub(job.end_sample);
            let skip = if job.is_final {
                lag > MAX_FINAL_LAG_SAMPLES
            } else {
                lag > MAX_PARTIAL_LAG_SAMPLES
            };

            if skip {
                if job.is_final {
                    log::warn!(
                        "Dropping final {:.1}s behind real time to catch up",
                        lag as f64 / SAMPLE_RATE as f64
                    );
                    gap_pending = true;
                }
            } else {
                // The shared engine may have been unloaded while idle (e.g. the
                // mic bubble's battery saver cleared it between jobs). Reload
                // on demand instead of dropping the job, so live subtitles are
                // never interrupted by an unload.
                let engine_arc = match engine::get_engine() {
                    Some(arc) => Some(arc),
                    None => {
                        let _ = engine::ensure_loaded(&mut env, service_obj);
                        engine::get_engine()
                    }
                };
                if let Some(engine_arc) = engine_arc {
                    let audio_secs = job.samples.len() as f64 / SAMPLE_RATE as f64;
                    let started = std::time::Instant::now();
                    let res = engine::transcribe_shared(&engine_arc, job.samples);
                    let elapsed = started.elapsed().as_secs_f64();
                    log::info!(
                        "Subtitle {} job: {:.1}s audio in {:.2}s (lag {:.1}s)",
                        if job.is_final { "final" } else { "partial" },
                        audio_secs,
                        elapsed,
                        lag as f64 / SAMPLE_RATE as f64,
                    );

                    // Track transcription speed (EMA) so the pusher can predict
                    // job costs; also reflects thermal throttling over time.
                    let sample = (elapsed / audio_secs * 1000.0) as u32;
                    let old = rtf_milli.load(Ordering::SeqCst);
                    let ema = if old == 0 { sample } else { (old * 7 + sample * 3) / 10 };
                    rtf_milli.store(ema, Ordering::SeqCst);

                    if let Ok(r) = res {
                        let text = r.trim();
                        if !text.is_empty() && gap_pending {
                            // Mark the dropped stretch so the transcript doesn't
                            // silently glue unrelated sentences together.
                            deliver(&mut env, "…", true);
                            gap_pending = false;
                        }
                        if !text.is_empty() || job.is_final {
                            deliver(&mut env, text, job.is_final);
                        }
                    }
                }
            }

            if job.is_final {
                pending_finals.fetch_sub(1, Ordering::SeqCst);
            }
            worker_busy.store(false, Ordering::SeqCst);
        }
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_LiveSubtitleService_cleanupNative(
    _env: JNIEnv,
    _class: JClass,
) {
    // Dropping the state drops the sender; the worker exits once the queue drains.
    *LIVE_STATE.lock().unwrap() = None;
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_LiveSubtitleService_pushAudio(
    env: JNIEnv,
    _class: JClass,
    data: jni::objects::JFloatArray,
    length: jni::sys::jint,
) {
    let len = length as usize;
    if len == 0 {
        return;
    }
    let mut input = vec![0.0f32; len];
    if env.get_float_array_region(&data, 0, &mut input).is_err() {
        return;
    }

    let mut guard = LIVE_STATE.lock().unwrap();
    let state = match guard.as_mut() {
        Some(s) => s,
        None => return,
    };

    let stream_pos = state.total_pushed.fetch_add(len as u64, Ordering::SeqCst) + len as u64;

    let rms = (input.iter().map(|&x| x * x).sum::<f32>() / len as f32).sqrt();
    let is_sound = rms >= SPEECH_RMS;

    if !state.has_speech {
        if is_sound {
            // Speech begins: seed the segment with the pre-roll so the first
            // word isn't clipped.
            state.segment = std::mem::take(&mut state.preroll);
            state.segment.extend_from_slice(&input);
            state.has_speech = true;
            state.silence_run = 0;
            state.samples_since_tick = state.segment.len();
        } else {
            state.preroll.extend_from_slice(&input);
            let excess = state.preroll.len().saturating_sub(PREROLL_SAMPLES);
            if excess > 0 {
                state.preroll.drain(..excess);
            }
            return;
        }
    } else {
        state.segment.extend_from_slice(&input);
        state.samples_since_tick += len;
        if is_sound {
            state.silence_run = 0;
        } else {
            state.silence_run += len;
        }
    }

    let silence_done = state.silence_run >= FINALIZE_SILENCE_SAMPLES;
    if silence_done || state.segment.len() >= MAX_SEGMENT_SAMPLES {
        let mut samples = std::mem::take(&mut state.segment);
        if silence_done {
            // Drop most of the trailing silence; keep a short tail.
            let keep = samples.len() - state.silence_run + FINAL_TAIL_SAMPLES;
            samples.truncate(keep.min(samples.len()));
            state.has_speech = false;
            state.preroll.clear();
        } else {
            // Forced cut mid-speech: split at the quietest point in the last
            // few seconds and carry the remainder into the next segment so no
            // word is chopped in half.
            let from = samples.len().saturating_sub(3 * SAMPLE_RATE);
            let split = crate::audio::find_quietest_split(&samples, from, samples.len());
            state.segment = samples.split_off(split);
        }
        state.silence_run = 0;
        state.samples_since_tick = state.segment.len();

        if samples.len() >= MIN_SEGMENT_SAMPLES {
            // Finals are always queued (the worker may still drop them if
            // they go stale — see MAX_FINAL_LAG_SAMPLES).
            state.worker_busy.store(true, Ordering::SeqCst);
            state.pending_finals.fetch_add(1, Ordering::SeqCst);
            let _ = state.worker_tx.send(Job {
                samples,
                is_final: true,
                end_sample: stream_pos,
            });
        }
    } else if state.samples_since_tick >= TICK_SAMPLES
        && !state.worker_busy.load(Ordering::SeqCst)
        && state.pending_finals.load(Ordering::SeqCst) == 0
        && partial_affordable(state)
    {
        // Partial update, only while the worker is idle, no final is waiting
        // and the job is predicted to be cheap (latest-wins; finals first) so
        // a slow device never queues up work and drifts behind real time.
        state.worker_busy.store(true, Ordering::SeqCst);
        state.samples_since_tick = 0;
        let _ = state.worker_tx.send(Job {
            samples: state.segment.clone(),
            is_final: false,
            end_sample: stream_pos,
        });
    }
}

/// Whether a partial of the current segment is predicted to transcribe within
/// [`MAX_PARTIAL_COST_SECS`], based on the worker's measured speed. As the
/// segment grows (or the device slows down under thermal throttling) partials
/// stop, leaving the worker free for the finals that carry the transcript.
fn partial_affordable(state: &LiveSubtitleState) -> bool {
    let rtf = state.rtf_milli.load(Ordering::SeqCst) as f32 / 1000.0;
    let segment_secs = state.segment.len() as f32 / SAMPLE_RATE as f32;
    segment_secs * rtf <= MAX_PARTIAL_COST_SECS
}
