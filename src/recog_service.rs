//! Native backend for `VoiceRecognitionService`, the `android.speech.RecognitionService`
//! implementation that lets *other* keyboards/apps (SwiftKey, Gboard, …) use this app
//! as their offline speech-to-text provider via the system `SpeechRecognizer` API.
//!
//! Unlike the IME / `RecognizeActivity` surfaces (which have their own UI and a manual
//! "tap to stop" control via `voice_session`), a `RecognitionService` has no UI of its
//! own: the calling keyboard expects *us* to decide when the user has finished speaking.
//! So this module adds trailing-silence endpointing on top of the same `engine` model,
//! and finalises automatically (it also honours an explicit `stopListening`/`cancel`).

use std::fs::File;
use std::io::{ErrorKind, Read};
use std::os::fd::FromRawFd;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use jni::objects::{GlobalRef, JClass, JObject};
use jni::sys::jint;
use jni::JNIEnv;
use once_cell::sync::Lazy;

use crate::engine;
use crate::voice_session::SendStream;

// --- Endpointing / VAD tuning -------------------------------------------------
// These are deliberately simple heuristics on the smoothed mic level. Mic gain
// varies a lot between devices, so they may need tuning; finalisation always
// transcribes whatever was captured, so a mis-tuned threshold only affects the
// auto-stop *timing*, never whether text is returned.
//
/// Absolute smoothed level (0..1) that must be exceeded to count as speech.
const MIN_SPEECH_LEVEL: f32 = 0.12;
/// How far above the running noise floor a level must be to count as speech.
const SPEECH_MARGIN: f32 = 0.08;
/// Trailing silence after speech that triggers auto-finalisation.
const SILENCE_MS: u64 = 1500;
/// If no speech is ever detected, finalise after this long anyway.
const NO_SPEECH_TIMEOUT_MS: u64 = 7000;
/// Hard cap on a single utterance (the engine internally chunks long audio).
const MAX_SESSION_MS: u64 = 60000;
/// Throttle interval for `rmsChanged` UI callbacks.
const LEVEL_UPDATE_MS: u64 = 50;

// --- Caller-supplied audio (RecognizerIntent.EXTRA_AUDIO_SOURCE) --------------
/// Rate the model runs at; caller audio is resampled to it.
const TARGET_SAMPLE_RATE: u32 = 16000;
/// Read size for the caller's descriptor.
const READ_CHUNK_BYTES: usize = 8192;
/// How long to block waiting for caller audio before re-checking for cancellation.
const POLL_TIMEOUT_MS: i32 = 100;

// Mirror of the android.media.AudioFormat encodings a caller may name.
const ENCODING_PCM_16BIT: i32 = 2;
const ENCODING_PCM_8BIT: i32 = 3;
const ENCODING_PCM_FLOAT: i32 = 4;

// Mirror of android.speech.SpeechRecognizer error codes we report.
const ERROR_AUDIO: i32 = 3;
const ERROR_SERVER: i32 = 4;
const ERROR_NO_MATCH: i32 = 7;

/// State shared between the audio callback, the endpoint-monitor thread and the
/// finaliser. Deliberately does NOT hold the cpal stream, to avoid an Arc cycle
/// (the stream's callback holds an `Arc<Endpoint>`).
struct Endpoint {
    audio_buffer: Mutex<Vec<f32>>,
    last_voice: Mutex<Instant>,
    noise_floor: Mutex<f32>,
    last_level_sent: Mutex<Instant>,
    speech_started: AtomicBool,
    finalized: AtomicBool,
    cancelled: AtomicBool,
    started_at: Instant,
    /// A caller streaming its own audio owns the endpoint: the session runs until the audio is
    /// closed or `stopListening` arrives, so trailing-silence auto-stop must not cut it short.
    caller_endpointed: bool,
    jvm: Arc<jni::JavaVM>,
    target: GlobalRef,
}

struct Session {
    shared: Arc<Endpoint>,
    stream: Arc<Mutex<Option<SendStream>>>,
}

static SESSION: Lazy<Mutex<Option<Session>>> = Lazy::new(|| Mutex::new(None));

// --- JNI callbacks into VoiceRecognitionService -------------------------------

fn call_void(env: &mut JNIEnv, obj: &JObject, method: &str) {
    let _ = env.call_method(obj, method, "()V", &[]);
}

fn call_rms(env: &mut JNIEnv, obj: &JObject, rms_db: f32) {
    let _ = env.call_method(obj, "onRmsChanged", "(F)V", &[rms_db.into()]);
}

fn call_error(env: &mut JNIEnv, obj: &JObject, code: i32) {
    let _ = env.call_method(obj, "onError", "(I)V", &[code.into()]);
}

fn call_results(env: &mut JNIEnv, obj: &JObject, text: &str) {
    if let Ok(jtxt) = env.new_string(text) {
        let _ = env.call_method(
            obj,
            "onResults",
            "(Ljava/lang/String;)V",
            &[(&jtxt).into()],
        );
    }
}

// --- JNI entry points ---------------------------------------------------------

/// Called from `onCreate`. Warms up the model in the background so the first
/// recognition after a cold bind is as fast as possible.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_VoiceRecognitionService_initNative(
    env: JNIEnv,
    _class: JClass,
    service: JObject,
) {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info),
    );

    let jvm = match env.get_java_vm() {
        Ok(vm) => Arc::new(vm),
        Err(_) => return,
    };
    let target_ref = match env.new_global_ref(&service) {
        Ok(r) => r,
        Err(_) => return,
    };

    std::thread::spawn(move || {
        let _ = engine::ensure_loaded_from_thread(&jvm, &target_ref);
    });
}

/// Called from `onStartListening`. Begins microphone capture and arms the
/// silence-based endpoint monitor.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_VoiceRecognitionService_startListening(
    env: JNIEnv,
    _class: JClass,
    service: JObject,
) {
    let jvm = match env.get_java_vm() {
        Ok(vm) => Arc::new(vm),
        Err(_) => return,
    };
    let target = match env.new_global_ref(&service) {
        Ok(r) => r,
        Err(_) => return,
    };

    let (shared, stream_holder) = match begin_session(&jvm, target, false) {
        Some(session) => session,
        None => return,
    };

    // Open the microphone (16 kHz mono, matching the model + voice_session).
    let host = cpal::default_host();
    let device = match host.default_input_device() {
        Some(d) => d,
        None => {
            let mut env2 = jvm.attach_current_thread().unwrap();
            call_error(&mut env2, shared.target.as_obj(), ERROR_AUDIO);
            return;
        }
    };
    let config = cpal::StreamConfig {
        channels: 1,
        sample_rate: cpal::SampleRate(16000),
        buffer_size: cpal::BufferSize::Default,
    };

    let cb_shared = shared.clone();
    let stream = device.build_input_stream(
        &config,
        move |data: &[f32], _: &_| audio_callback(&cb_shared, data),
        |e| log::error!("RecognitionService stream error: {}", e),
        None,
    );

    match stream {
        Ok(s) => {
            s.play().ok();
            *stream_holder.lock().unwrap() = Some(SendStream(s));
        }
        Err(e) => {
            log::error!("Failed to open microphone: {}", e);
            let mut env2 = jvm.attach_current_thread().unwrap();
            call_error(&mut env2, shared.target.as_obj(), ERROR_AUDIO);
            return;
        }
    }

    // Endpoint monitor.
    let mon_shared = shared.clone();
    let mon_stream = stream_holder.clone();
    std::thread::spawn(move || endpoint_monitor(mon_shared, mon_stream));

    *SESSION.lock().unwrap() = Some(Session {
        shared,
        stream: stream_holder,
    });
}

/// Called from `onStartListening` when the caller passed
/// `RecognizerIntent.EXTRA_AUDIO_SOURCE`. Transcribes the audio arriving on `fd` instead of
/// opening the microphone, which is what lets an app that is not the system-selected recognizer's
/// owner use this engine at all — see the Java side for why the microphone path cannot serve it.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_VoiceRecognitionService_startListeningFromAudioSource(
    env: JNIEnv,
    _class: JClass,
    service: JObject,
    fd: jint,
    sample_rate: jint,
    channel_count: jint,
    encoding: jint,
) {
    let jvm = match env.get_java_vm() {
        Ok(vm) => Arc::new(vm),
        Err(_) => return,
    };
    let target = match env.new_global_ref(&service) {
        Ok(r) => r,
        Err(_) => return,
    };

    let format = match SourceFormat::new(sample_rate, channel_count, encoding) {
        Some(format) => format,
        None => {
            log::error!(
                "unsupported audio source: {} Hz, {} channels, encoding {}",
                sample_rate,
                channel_count,
                encoding
            );
            drop(File::from_raw_fd(fd));
            if let Ok(mut env2) = jvm.attach_current_thread() {
                call_error(&mut env2, target.as_obj(), ERROR_AUDIO);
            }
            return;
        }
    };

    let (shared, stream_holder) = match begin_session(&jvm, target, true) {
        Some(session) => session,
        None => {
            drop(File::from_raw_fd(fd));
            return;
        }
    };

    let reader_shared = shared.clone();
    let reader_stream = stream_holder.clone();
    std::thread::spawn(move || read_caller_audio(reader_shared, reader_stream, fd, format));

    let mon_shared = shared.clone();
    let mon_stream = stream_holder.clone();
    std::thread::spawn(move || endpoint_monitor(mon_shared, mon_stream));

    *SESSION.lock().unwrap() = Some(Session {
        shared,
        stream: stream_holder,
    });
}

/// Called from `onStopListening`: the keyboard asked us to finish now. Finalise
/// with whatever we've captured so far.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_VoiceRecognitionService_stopListening(
    _env: JNIEnv,
    _class: JClass,
) {
    let session = SESSION.lock().unwrap().as_ref().map(|s| (s.shared.clone(), s.stream.clone()));
    if let Some((shared, stream)) = session {
        std::thread::spawn(move || finalize(shared, stream));
    }
}

/// Called from `onCancel`: discard everything, return nothing.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_VoiceRecognitionService_cancelNative(
    _env: JNIEnv,
    _class: JClass,
) {
    let mut guard = SESSION.lock().unwrap();
    if let Some(session) = guard.as_ref() {
        session.shared.cancelled.store(true, Ordering::SeqCst);
        session.shared.finalized.store(true, Ordering::SeqCst);
        *session.stream.lock().unwrap() = None;
    }
    *guard = None;
}

/// Called from `onDestroy`.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_VoiceRecognitionService_destroyNative(
    env: JNIEnv,
    class: JClass,
) {
    Java_dev_notune_transcribe_VoiceRecognitionService_cancelNative(env, class);
}

// --- Session lifecycle --------------------------------------------------------

/// Replaces any leftover session with fresh shared state and tells the caller we are ready.
/// Returns `None` when the JVM cannot be attached, in which case no session was installed.
fn begin_session(
    jvm: &Arc<jni::JavaVM>,
    target: GlobalRef,
    caller_endpointed: bool,
) -> Option<(Arc<Endpoint>, Arc<Mutex<Option<SendStream>>>)> {
    // Tear down any session that is still around (e.g. the keyboard called
    // startListening twice without cancel) so its monitor/finaliser can never
    // deliver stale results to this new session.
    {
        let mut guard = SESSION.lock().unwrap();
        if let Some(old) = guard.take() {
            old.shared.cancelled.store(true, Ordering::SeqCst);
            old.shared.finalized.store(true, Ordering::SeqCst);
            *old.stream.lock().unwrap() = None;
        }
    }

    let now = Instant::now();
    let shared = Arc::new(Endpoint {
        audio_buffer: Mutex::new(Vec::new()),
        last_voice: Mutex::new(now),
        noise_floor: Mutex::new(0.0),
        last_level_sent: Mutex::new(now),
        speech_started: AtomicBool::new(false),
        finalized: AtomicBool::new(false),
        cancelled: AtomicBool::new(false),
        started_at: now,
        caller_endpointed,
        jvm: jvm.clone(),
        target,
    });

    // Tell the caller we're ready to receive speech.
    let mut env = jvm.attach_current_thread().ok()?;
    call_void(&mut env, shared.target.as_obj(), "onReadyForSpeech");

    Some((shared, Arc::new(Mutex::new(None))))
}

// --- Caller-supplied audio ----------------------------------------------------

/// The PCM layout a caller declared for `EXTRA_AUDIO_SOURCE`.
struct SourceFormat {
    sample_rate: u32,
    channels: usize,
    bytes_per_sample: usize,
    encoding: i32,
}

impl SourceFormat {
    fn new(sample_rate: i32, channels: i32, encoding: i32) -> Option<Self> {
        let bytes_per_sample = match encoding {
            ENCODING_PCM_8BIT => 1,
            ENCODING_PCM_16BIT => 2,
            ENCODING_PCM_FLOAT => 4,
            _ => return None,
        };
        if !(4000..=192_000).contains(&sample_rate) || !(1..=8).contains(&channels) {
            return None;
        }
        Some(Self {
            sample_rate: sample_rate as u32,
            channels: channels as usize,
            bytes_per_sample,
            encoding,
        })
    }

    fn frame_bytes(&self) -> usize {
        self.bytes_per_sample * self.channels
    }
}

/// Feeds the endpoint from the caller's descriptor until the audio is closed, the session is
/// cancelled, or reading fails. Closing the descriptor ends the utterance, the same end of audio
/// the microphone path gets from its own recorder stopping.
fn read_caller_audio(
    shared: Arc<Endpoint>,
    stream: Arc<Mutex<Option<SendStream>>>,
    fd: i32,
    format: SourceFormat,
) {
    // SAFETY: the descriptor was detached from the caller's ParcelFileDescriptor, so this File is
    // its sole owner and closes it on drop.
    let mut source = unsafe { File::from_raw_fd(fd) };
    let mut resampler = Resampler::new(format.sample_rate, TARGET_SAMPLE_RATE);
    let mut raw = [0u8; READ_CHUNK_BYTES];
    let mut carry: Vec<u8> = Vec::new();
    let mut mono: Vec<f32> = Vec::new();
    let mut frames: Vec<f32> = Vec::new();

    while wait_readable(fd, &shared) {
        match source.read(&mut raw) {
            Ok(0) => break, // the caller closed the audio: the utterance is complete
            Ok(read) => {
                carry.extend_from_slice(&raw[..read]);
                mono.clear();
                drain_mono_frames(&mut carry, &format, &mut mono);
                if mono.is_empty() {
                    continue;
                }
                frames.clear();
                resampler.push(&mono, &mut frames);
                if !frames.is_empty() {
                    audio_callback(&shared, &frames);
                }
            }
            Err(ref e) if e.kind() == ErrorKind::Interrupted => continue,
            Err(e) => {
                log::error!("audio source read failed: {}", e);
                break;
            }
        }
    }

    finalize(shared, stream);
}

/// Blocks until the descriptor has audio to read. Returns false once the session has ended, so a
/// cancel never leaves this thread parked on a descriptor the caller stopped writing to.
fn wait_readable(fd: i32, shared: &Arc<Endpoint>) -> bool {
    loop {
        if shared.cancelled.load(Ordering::SeqCst) || shared.finalized.load(Ordering::SeqCst) {
            return false;
        }

        let mut poll_fd = libc::pollfd {
            fd,
            events: libc::POLLIN,
            revents: 0,
        };
        // SAFETY: a single initialised pollfd naming a descriptor this thread owns.
        let ready = unsafe { libc::poll(&mut poll_fd, 1, POLL_TIMEOUT_MS) };

        if ready > 0 {
            return true;
        }
        if ready < 0 {
            let err = std::io::Error::last_os_error();
            if err.kind() == ErrorKind::Interrupted {
                continue;
            }
            log::error!("audio source poll failed: {}", err);
            return false;
        }
        // Timed out with nothing to read: loop round to re-check for cancellation.
    }
}

/// Decodes every whole frame buffered in `carry` into mono samples, leaving a partial frame behind.
fn drain_mono_frames(carry: &mut Vec<u8>, format: &SourceFormat, out: &mut Vec<f32>) {
    let frame_bytes = format.frame_bytes();
    let complete = carry.len() / frame_bytes;
    if complete == 0 {
        return;
    }

    for frame in carry[..complete * frame_bytes].chunks_exact(frame_bytes) {
        let mut sum = 0.0f32;
        for sample in frame.chunks_exact(format.bytes_per_sample) {
            sum += decode_sample(sample, format.encoding);
        }
        out.push(sum / format.channels as f32);
    }

    carry.drain(..complete * frame_bytes);
}

fn decode_sample(bytes: &[u8], encoding: i32) -> f32 {
    match encoding {
        ENCODING_PCM_8BIT => (bytes[0] as f32 - 128.0) / 128.0,
        ENCODING_PCM_FLOAT => f32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]),
        _ => i16::from_le_bytes([bytes[0], bytes[1]]) as f32 / 32768.0,
    }
}

/// Linear resampler that keeps its fractional read position across chunks, so a caller sending
/// something other than 16 kHz still produces a continuous stream for the model.
struct Resampler {
    step: f64,
    pos: f64,
    pending: Vec<f32>,
}

impl Resampler {
    fn new(from: u32, to: u32) -> Self {
        Self {
            step: from as f64 / to as f64,
            pos: 0.0,
            pending: Vec::new(),
        }
    }

    fn push(&mut self, input: &[f32], out: &mut Vec<f32>) {
        if self.step == 1.0 {
            out.extend_from_slice(input);
            return;
        }

        self.pending.extend_from_slice(input);
        while self.pos + 1.0 < self.pending.len() as f64 {
            let index = self.pos as usize;
            let frac = (self.pos - index as f64) as f32;
            out.push(self.pending[index] * (1.0 - frac) + self.pending[index + 1] * frac);
            self.pos += self.step;
        }

        let consumed = self.pos as usize;
        if consumed > 0 {
            self.pending.drain(..consumed);
            self.pos -= consumed as f64;
        }
    }
}

// --- Audio + endpointing ------------------------------------------------------

fn audio_callback(shared: &Arc<Endpoint>, data: &[f32]) {
    if shared.finalized.load(Ordering::SeqCst) {
        return;
    }

    shared.audio_buffer.lock().unwrap().extend_from_slice(data);

    // RMS -> smoothed level in 0..1 (same scaling as voice_session).
    let mut sum = 0.0f32;
    for &x in data {
        sum += x * x;
    }
    let rms = (sum / (data.len().max(1) as f32)).sqrt();
    let level = (rms * 6.0).clamp(0.0, 1.0);

    let floor = *shared.noise_floor.lock().unwrap();
    let is_speech = level > MIN_SPEECH_LEVEL && level > floor + SPEECH_MARGIN;

    if is_speech {
        *shared.last_voice.lock().unwrap() = Instant::now();
        // First detected speech -> notify beginningOfSpeech exactly once.
        if shared
            .speech_started
            .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
            .is_ok()
        {
            if let Ok(mut env) = shared.jvm.attach_current_thread() {
                call_void(&mut env, shared.target.as_obj(), "onBeginningOfSpeech");
            }
        }
    } else {
        // Slowly adapt the noise floor while no speech is present.
        let mut nf = shared.noise_floor.lock().unwrap();
        *nf = *nf * 0.95 + level * 0.05;
    }

    // Throttled mic-level updates for the keyboard's waveform UI.
    let mut last = shared.last_level_sent.lock().unwrap();
    if last.elapsed() >= Duration::from_millis(LEVEL_UPDATE_MS) {
        *last = Instant::now();
        drop(last);
        if let Ok(mut env) = shared.jvm.attach_current_thread() {
            call_rms(&mut env, shared.target.as_obj(), level * 10.0);
        }
    }
}

fn endpoint_monitor(shared: Arc<Endpoint>, stream: Arc<Mutex<Option<SendStream>>>) {
    loop {
        std::thread::sleep(Duration::from_millis(100));

        if shared.cancelled.load(Ordering::SeqCst) || shared.finalized.load(Ordering::SeqCst) {
            return;
        }

        let elapsed = shared.started_at.elapsed();
        let speech = shared.speech_started.load(Ordering::SeqCst);
        let silence = shared.last_voice.lock().unwrap().elapsed();

        // A caller streaming its own audio decides when the utterance ends; only the hard cap,
        // which bounds how much audio a session may buffer, still applies to it.
        let done = if shared.caller_endpointed {
            elapsed >= Duration::from_millis(MAX_SESSION_MS)
        } else {
            (speech && silence >= Duration::from_millis(SILENCE_MS))
                || elapsed >= Duration::from_millis(MAX_SESSION_MS)
                || (!speech && elapsed >= Duration::from_millis(NO_SPEECH_TIMEOUT_MS))
        };

        if done {
            finalize(shared, stream);
            return;
        }
    }
}

/// Stop capture, run the model on the buffered audio and deliver results/error.
/// Idempotent: only the first caller (monitor or explicit stop) does the work.
fn finalize(shared: Arc<Endpoint>, stream: Arc<Mutex<Option<SendStream>>>) {
    if shared
        .finalized
        .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
        .is_err()
    {
        return; // already finalised/cancelled
    }

    // Stop the microphone (also drops the audio callback's Arc<Endpoint>).
    *stream.lock().unwrap() = None;

    let buffer = shared.audio_buffer.lock().unwrap().clone();
    let speech = shared.speech_started.load(Ordering::SeqCst);

    let mut env = match shared.jvm.attach_current_thread() {
        Ok(e) => e,
        Err(_) => return,
    };
    let target = shared.target.as_obj();

    if speech {
        call_void(&mut env, target, "onEndOfSpeech");
    }

    // ~0.2s minimum of audio to bother transcribing.
    if buffer.len() < 3200 {
        call_error(&mut env, target, ERROR_NO_MATCH);
        clear_session(&shared);
        return;
    }

    if engine::get_engine().is_none() {
        if engine::ensure_loaded(&mut env, target).is_err() {
            call_error(&mut env, target, ERROR_SERVER);
            clear_session(&shared);
            return;
        }
    }

    match engine::get_engine() {
        Some(eng_arc) => {
            let res = engine::transcribe_shared(&eng_arc, buffer);
            match res {
                Ok(text) if !text.trim().is_empty() => call_results(&mut env, target, &text),
                Ok(_) => call_error(&mut env, target, ERROR_NO_MATCH),
                Err(e) => {
                    log::error!("Transcription failed: {}", e);
                    call_error(&mut env, target, ERROR_SERVER);
                }
            }
        }
        None => call_error(&mut env, target, ERROR_SERVER),
    }

    clear_session(&shared);
}

/// Clear the global session, but only if it is still *this* session — a newer
/// `startListening` may already have installed a fresh one.
fn clear_session(shared: &Arc<Endpoint>) {
    let mut guard = SESSION.lock().unwrap();
    if let Some(s) = guard.as_ref() {
        if Arc::ptr_eq(&s.shared, shared) {
            *guard = None;
        }
    }
}
