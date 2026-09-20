//! One microphone recording, its ordered inference work, and its JNI callbacks.

use std::collections::VecDeque;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use crossbeam_channel::{Receiver, Sender};
use jni::objects::{GlobalRef, JObject, JValue};
use jni::JNIEnv;

use crate::engine;
use crate::streaming::{
    CutCause, Segmenter, SegmenterConfig, DEFAULT_SPLIT_SECONDS, MAX_SPLIT_SECONDS,
    MIN_SPLIT_SECONDS,
};

const MIN_SPEECH_LEVEL: f32 = 0.12;
const SPEECH_MARGIN: f32 = 0.08;
const AUTO_STOP_SILENCE_MS: u64 = 2000;
const AUTO_STOP_NO_SPEECH_MS: u64 = 8000;
const SPLIT_SECONDS_FILE: &str = "pause_split_seconds";

pub const OUTCOME_SUCCESS: i32 = 0;
pub const OUTCOME_RETRYABLE: i32 = 1;
pub const OUTCOME_CANCELLED: i32 = 2;
pub const OUTCOME_INTERRUPTED: i32 = 3;
pub const OUTCOME_FATAL: i32 = 4;

pub struct SendStream(#[allow(dead_code)] pub cpal::Stream);
unsafe impl Send for SendStream {}
unsafe impl Sync for SendStream {}

#[derive(Clone)]
struct PieceJob {
    sequence: u64,
    samples: Vec<f32>,
    pause_before: f32,
    cause: CutCause,
    context: Option<ContextPiece>,
}

#[derive(Clone)]
struct ContextPiece {
    samples: Vec<f32>,
    text: String,
}

enum Job {
    Piece(PieceJob),
    End,
}

struct Endpointing {
    last_voice: Mutex<Instant>,
    noise_floor: Mutex<f32>,
    speech_started: AtomicBool,
}

struct Attempt {
    session_id: i64,
    cancelled: AtomicBool,
    processing: AtomicBool,
    terminal_sent: AtomicBool,
    retry_jobs: Mutex<VecDeque<PieceJob>>,
    delivered_texts: Mutex<Vec<String>>,
    failure: Mutex<Option<String>>,
}

impl Attempt {
    fn new(session_id: i64) -> Self {
        Self {
            session_id,
            cancelled: AtomicBool::new(false),
            processing: AtomicBool::new(true),
            terminal_sent: AtomicBool::new(false),
            retry_jobs: Mutex::new(VecDeque::new()),
            delivered_texts: Mutex::new(Vec::new()),
            failure: Mutex::new(None),
        }
    }

    fn retryable(&self) -> bool {
        !self.retry_jobs.lock().unwrap().is_empty()
    }
}

pub struct VoiceSessionState {
    pub stream: Option<SendStream>,
    pub jvm: Arc<jni::JavaVM>,
    pub target_ref: GlobalRef,
    pub last_level_sent: Arc<Mutex<Instant>>,
    pub session_active: Arc<AtomicBool>,
    files_dir: Option<PathBuf>,
    segmenter: Arc<Mutex<Segmenter>>,
    worker_tx: Option<Sender<Job>>,
    next_sequence: Arc<AtomicU64>,
    current: Option<Arc<Attempt>>,
}

fn notify_status(env: &mut JNIEnv, obj: &JObject, session_id: i64, msg: &str) {
    if let Ok(jmsg) = env.new_string(msg) {
        let _ = env.call_method(
            obj,
            "onDictationStatus",
            "(JLjava/lang/String;)V",
            &[JValue::Long(session_id), (&jmsg).into()],
        );
    }
}

fn notify_level(env: &mut JNIEnv, obj: &JObject, session_id: i64, level: f32) {
    let _ = env.call_method(
        obj,
        "onDictationLevel",
        "(JF)V",
        &[JValue::Long(session_id), level.into()],
    );
}

fn notify_piece(
    env: &mut JNIEnv,
    obj: &JObject,
    session_id: i64,
    sequence: u64,
    text: &str,
    pause_before: f32,
) -> Result<bool, String> {
    let jtxt = env.new_string(text).map_err(|e| e.to_string())?;
    let result = env.call_method(
        obj,
        "onTranscriptPiece",
        "(JJLjava/lang/String;F)Z",
        &[
            JValue::Long(session_id),
            JValue::Long(sequence as i64),
            (&jtxt).into(),
            pause_before.into(),
        ],
    );
    match result {
        Ok(v) => v.z().map_err(|e| e.to_string()),
        Err(e) => {
            let _ = env.exception_clear();
            Err(e.to_string())
        }
    }
}

fn notify_complete(env: &mut JNIEnv, obj: &JObject, attempt: &Attempt, outcome: i32, error: &str) {
    if attempt.terminal_sent.swap(true, Ordering::SeqCst) {
        return;
    }
    let text = attempt.delivered_texts.lock().unwrap().join(" ");
    if let (Ok(jtext), Ok(jerror)) = (env.new_string(text), env.new_string(error)) {
        let result = env.call_method(
            obj,
            "onDictationComplete",
            "(JILjava/lang/String;Ljava/lang/String;)V",
            &[
                JValue::Long(attempt.session_id),
                JValue::Int(outcome),
                (&jtext).into(),
                (&jerror).into(),
            ],
        );
        if result.is_err() {
            let _ = env.exception_clear();
        }
    }
}

fn notify_auto_stop(env: &mut JNIEnv, obj: &JObject, session_id: i64) {
    if let Err(e) = env.call_method(obj, "onAutoStop", "(J)V", &[JValue::Long(session_id)]) {
        // A Java callback failure must not leave a pending exception on a native
        // capture thread. In particular, detaching with NoSuchMethodError pending
        // aborts Android's runtime instead of allowing capture shutdown to finish.
        let _ = env.exception_clear();
        log::error!("onAutoStop callback failed: {}", e);
    }
}

pub fn init_session(mut env: JNIEnv, target: JObject) -> VoiceSessionState {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info),
    );
    let files_dir = crate::assets::files_dir(&mut env, &target)
        .map_err(|e| log::warn!("Failed to resolve filesDir for dictation settings: {}", e))
        .ok();
    let vm = Arc::new(env.get_java_vm().expect("Failed to get JavaVM"));
    let target_ref = env.new_global_ref(&target).expect("Failed to ref target");
    let state = VoiceSessionState {
        stream: None,
        jvm: vm.clone(),
        target_ref: target_ref.clone(),
        last_level_sent: Arc::new(Mutex::new(Instant::now())),
        session_active: Arc::new(AtomicBool::new(false)),
        files_dir,
        segmenter: Arc::new(Mutex::new(Segmenter::new(SegmenterConfig::dictation()))),
        worker_tx: None,
        next_sequence: Arc::new(AtomicU64::new(0)),
        current: None,
    };
    std::thread::spawn(move || {
        let _ = engine::ensure_loaded_from_thread(&vm, &target_ref);
    });
    state
}

fn parse_pause_seconds(text: &str) -> Option<f32> {
    let seconds = text.trim().parse::<f32>().ok()?;
    (seconds.is_finite() && (MIN_SPLIT_SECONDS..=MAX_SPLIT_SECONDS).contains(&seconds))
        .then_some(seconds)
}

fn split_seconds(files_dir: Option<&Path>) -> f32 {
    files_dir
        .and_then(|dir| std::fs::read_to_string(dir.join(SPLIT_SECONDS_FILE)).ok())
        .as_deref()
        .and_then(parse_pause_seconds)
        .unwrap_or(DEFAULT_SPLIT_SECONDS)
}

fn fail_piece(attempt: &Attempt, piece: PieceJob, error: String) {
    let mut failure = attempt.failure.lock().unwrap();
    if failure.is_none() {
        *failure = Some(error);
    }
    attempt.retry_jobs.lock().unwrap().push_back(piece);
}

fn normalized_words(text: &str) -> Vec<String> {
    let mut words = Vec::new();
    let mut word = String::new();
    for ch in text.chars() {
        if ch.is_alphanumeric() {
            word.extend(ch.to_lowercase());
        } else if !word.is_empty() {
            words.push(std::mem::take(&mut word));
        }
    }
    if !word.is_empty() {
        words.push(word);
    }
    words
}

/// Returns original candidate wording after an exact case/punctuation-normalized
/// prior-word prefix. Only separator punctuation at the join is removed.
fn suffix_after_prefix(candidate: &str, prior: &str) -> Option<String> {
    let prior_words = normalized_words(prior);
    if prior_words.is_empty() {
        return None;
    }
    let mut candidate_words = Vec::new();
    let mut word = String::new();
    let mut last_prefix_end = 0;
    for (offset, ch) in candidate.char_indices() {
        if ch.is_alphanumeric() {
            word.extend(ch.to_lowercase());
            if candidate_words.len() + 1 == prior_words.len() {
                last_prefix_end = offset + ch.len_utf8();
            }
        } else if !word.is_empty() {
            candidate_words.push(std::mem::take(&mut word));
        }
    }
    if !word.is_empty() {
        candidate_words.push(word);
        if candidate_words.len() == prior_words.len() {
            last_prefix_end = candidate.len();
        }
    }
    if candidate_words.len() < prior_words.len()
        || candidate_words[..prior_words.len()] != prior_words
    {
        return None;
    }
    if candidate_words.len() == prior_words.len() {
        return Some(String::new());
    }
    let suffix = candidate[last_prefix_end..].trim_start_matches(|ch: char| {
        ch.is_whitespace() || matches!(ch, '.' | ',' | '!' | '?' | ';' | ':')
    });
    Some(suffix.to_string())
}

fn owned_tail_text(candidate: &str) -> String {
    candidate.trim().to_string()
}

fn settle_attempt(env: &mut JNIEnv, obj: &JObject, attempt: &Attempt) {
    attempt.processing.store(false, Ordering::SeqCst);
    match attempt.failure.lock().unwrap().clone() {
        Some(error) => {
            notify_status(env, obj, attempt.session_id, &format!("Error: {}", error));
            let outcome = if attempt.retryable() {
                OUTCOME_RETRYABLE
            } else {
                OUTCOME_FATAL
            };
            notify_complete(env, obj, attempt, outcome, &error);
        }
        None => {
            notify_status(env, obj, attempt.session_id, "Complete");
            notify_complete(env, obj, attempt, OUTCOME_SUCCESS, "");
        }
    }
}

fn spawn_worker(
    jvm: Arc<jni::JavaVM>,
    target_ref: GlobalRef,
    rx: Receiver<Job>,
    attempt: Arc<Attempt>,
) -> bool {
    let (ready_tx, ready_rx) = crossbeam_channel::bounded(1);
    std::thread::spawn(move || {
        let mut env = match jvm.attach_current_thread() {
            Ok(e) => e,
            Err(e) => {
                attempt.processing.store(false, Ordering::SeqCst);
                *attempt.failure.lock().unwrap() =
                    Some("transcription worker unavailable".to_string());
                let _ = ready_tx.send(false);
                log::error!("Transcription worker failed to attach: {}", e);
                return;
            }
        };
        let _ = ready_tx.send(true);
        let obj = target_ref.as_obj();
        let mut blocked = false;
        let mut previous: Option<ContextPiece> = None;
        while let Ok(job) = rx.recv() {
            if attempt.cancelled.load(Ordering::SeqCst) {
                attempt.processing.store(false, Ordering::SeqCst);
                return;
            }
            let mut piece = match job {
                Job::Piece(piece) => piece,
                Job::End => {
                    settle_attempt(&mut env, obj, &attempt);
                    return;
                }
            };
            if blocked {
                attempt.retry_jobs.lock().unwrap().push_back(piece);
                continue;
            }
            if engine::get_engine().is_none() && engine::ensure_loaded(&mut env, obj).is_err() {
                fail_piece(&attempt, piece, "model failed to load".to_string());
                blocked = true;
                continue;
            }
            let eng = match engine::get_engine() {
                Some(e) => e,
                None => {
                    fail_piece(&attempt, piece, "model not loaded".to_string());
                    blocked = true;
                    continue;
                }
            };
            if piece.cause == CutCause::Flush && piece.context.is_none() {
                piece.context = previous.clone();
            }
            let prepared_piece = piece.samples.clone();
            let samples = match &piece.context {
                Some(context) if piece.cause == CutCause::Flush => {
                    let mut samples = context.samples.clone();
                    samples.extend_from_slice(&prepared_piece);
                    samples
                }
                _ => prepared_piece.clone(),
            };
            match engine::transcribe_shared(&eng, samples) {
                Ok(text) => {
                    if attempt.cancelled.load(Ordering::SeqCst) {
                        attempt.processing.store(false, Ordering::SeqCst);
                        return;
                    }
                    let candidate = text.trim().to_string();
                    let delivered = match &piece.context {
                        Some(context) if piece.cause == CutCause::Flush => {
                            match suffix_after_prefix(&candidate, &context.text) {
                                Some(suffix) => suffix,
                                None => {
                                    // The combined re-decode may phrase the
                                    // already delivered context differently.
                                    // The tail is disjoint owned audio, so run
                                    // that alone and deliver only its words.
                                    match engine::transcribe_shared(&eng, prepared_piece.clone()) {
                                        Ok(tail) => owned_tail_text(&tail),
                                        Err(e) => {
                                            log::error!("final tail transcription failed: {}", e);
                                            fail_piece(&attempt, piece, e);
                                            blocked = true;
                                            continue;
                                        }
                                    }
                                }
                            }
                        }
                        _ => candidate,
                    };
                    match notify_piece(
                        &mut env,
                        obj,
                        attempt.session_id,
                        piece.sequence,
                        &delivered,
                        piece.pause_before,
                    ) {
                        Ok(true) => {
                            if !delivered.is_empty() {
                                attempt
                                    .delivered_texts
                                    .lock()
                                    .unwrap()
                                    .push(delivered.clone());
                            }
                            if piece.cause != CutCause::Flush {
                                previous = Some(ContextPiece {
                                    // Keep the original interval for retry.
                                    // Preparation is deterministic and cheap.
                                    samples: piece.samples.clone(),
                                    text: delivered,
                                });
                            }
                        }
                        Ok(false) => {
                            fail_piece(&attempt, piece, "text was not stored".to_string());
                            blocked = true;
                        }
                        Err(e) => {
                            fail_piece(&attempt, piece, format!("cannot deliver text ({})", e));
                            blocked = true;
                        }
                    }
                }
                Err(e) => {
                    log::error!("piece transcription failed: {}", e);
                    fail_piece(&attempt, piece, e);
                    blocked = true;
                }
            }
        }
        attempt.processing.store(false, Ordering::SeqCst);
    });
    ready_rx
        .recv_timeout(Duration::from_secs(5))
        .unwrap_or(false)
}

/// The production microphone seam. Tests can feed recorded PCM here in an isolated build.
fn send_piece(tx: &Sender<Job>, attempt: &Attempt, piece: PieceJob) -> bool {
    match tx.send(Job::Piece(piece)) {
        Ok(()) => true,
        Err(crossbeam_channel::SendError(Job::Piece(piece))) => {
            fail_piece(attempt, piece, "transcription worker stopped".to_string());
            false
        }
        Err(crossbeam_channel::SendError(Job::End)) => false,
    }
}

fn queue_audio(
    data: &[f32],
    segmenter: &Arc<Mutex<Segmenter>>,
    tx: &Sender<Job>,
    next_sequence: &Arc<AtomicU64>,
    attempt: &Arc<Attempt>,
) {
    if let Ok(mut segmenter) = segmenter.lock() {
        if let Some(piece) = segmenter.push(data) {
            let sequence = next_sequence.fetch_add(1, Ordering::SeqCst);
            send_piece(
                tx,
                attempt,
                PieceJob {
                    sequence,
                    samples: piece.samples,
                    pause_before: piece.pause_before,
                    cause: piece.cause,
                    context: None,
                },
            );
        }
    }
}

fn finish_worker(env: &mut JNIEnv, state: &mut VoiceSessionState, attempt: &Arc<Attempt>) {
    let sent = state
        .worker_tx
        .take()
        .is_some_and(|tx| tx.send(Job::End).is_ok());
    if !sent {
        if attempt.failure.lock().unwrap().is_none() {
            *attempt.failure.lock().unwrap() = Some("transcription worker stopped".to_string());
        }
        settle_attempt(env, state.target_ref.as_obj(), attempt);
    }
}

pub fn start_recording(
    mut env: JNIEnv,
    state: &mut VoiceSessionState,
    session_id: i64,
    auto_stop: bool,
) -> bool {
    if session_id <= 0 {
        return false;
    }
    if state.stream.is_some()
        || state
            .current
            .as_ref()
            .is_some_and(|a| a.processing.load(Ordering::SeqCst) || a.retryable())
    {
        notify_status(
            &mut env,
            state.target_ref.as_obj(),
            session_id,
            "Error: another dictation is not resolved",
        );
        return false;
    }
    let attempt = Arc::new(Attempt::new(session_id));
    state.current = Some(attempt.clone());
    let split_seconds = split_seconds(state.files_dir.as_deref());
    *state.segmenter.lock().unwrap() =
        Segmenter::new(SegmenterConfig::dictation_with_split_seconds(split_seconds));
    state.next_sequence.store(0, Ordering::SeqCst);
    let (tx, rx) = crossbeam_channel::unbounded::<Job>();
    if !spawn_worker(
        state.jvm.clone(),
        state.target_ref.clone(),
        rx,
        attempt.clone(),
    ) {
        attempt.processing.store(false, Ordering::SeqCst);
        if attempt.failure.lock().unwrap().is_none() {
            *attempt.failure.lock().unwrap() = Some("transcription worker unavailable".to_string());
        }
        settle_attempt(&mut env, state.target_ref.as_obj(), &attempt);
        return false;
    }
    state.worker_tx = Some(tx.clone());

    let device = match cpal::default_host().default_input_device() {
        Some(d) => d,
        None => {
            *attempt.failure.lock().unwrap() = Some("no microphone available".to_string());
            finish_worker(&mut env, state, &attempt);
            return false;
        }
    };
    let config = cpal::StreamConfig {
        channels: 1,
        sample_rate: cpal::SampleRate(16000),
        buffer_size: cpal::BufferSize::Default,
    };
    state.session_active.store(false, Ordering::SeqCst);
    let session_active = Arc::new(AtomicBool::new(true));
    state.session_active = session_active.clone();
    let endpoint = auto_stop.then(|| {
        Arc::new(Endpointing {
            last_voice: Mutex::new(Instant::now()),
            noise_floor: Mutex::new(0.0),
            speech_started: AtomicBool::new(false),
        })
    });
    let jvm = state.jvm.clone();
    let target_ref = state.target_ref.clone();
    let last_sent = state.last_level_sent.clone();
    let endpoint_cb = endpoint.clone();
    let segmenter = state.segmenter.clone();
    let next_sequence = state.next_sequence.clone();
    let capture_attempt = attempt.clone();
    let error_jvm = state.jvm.clone();
    let error_target = state.target_ref.clone();
    let error_active = session_active.clone();
    let error_attempt = attempt.clone();
    let stream = device.build_input_stream(
        &config,
        move |data: &[f32], _: &_| {
            queue_audio(data, &segmenter, &tx, &next_sequence, &capture_attempt);
            let rms = (data.iter().map(|x| x * x).sum::<f32>() / data.len().max(1) as f32).sqrt();
            let level = (rms * 6.0).clamp(0.0, 1.0);
            if let Some(ep) = &endpoint_cb {
                let floor = *ep.noise_floor.lock().unwrap();
                if level > MIN_SPEECH_LEVEL && level > floor + SPEECH_MARGIN {
                    *ep.last_voice.lock().unwrap() = Instant::now();
                    ep.speech_started.store(true, Ordering::SeqCst);
                } else {
                    let mut floor = ep.noise_floor.lock().unwrap();
                    *floor = *floor * 0.95 + level * 0.05;
                }
            }
            let mut last = last_sent.lock().unwrap();
            if last.elapsed() >= Duration::from_millis(50) {
                *last = Instant::now();
                if let Ok(mut env) = jvm.attach_current_thread() {
                    notify_level(&mut env, target_ref.as_obj(), session_id, level);
                }
            }
        },
        move |e| {
            log::error!("Stream err: {}", e);
            *error_attempt.failure.lock().unwrap() = Some(format!("microphone failed: {}", e));
            if error_active.swap(false, Ordering::SeqCst) {
                if let Ok(mut env) = error_jvm.attach_current_thread() {
                    notify_auto_stop(&mut env, error_target.as_obj(), session_id);
                }
            }
        },
        None,
    );
    let stream = match stream {
        Ok(stream) => stream,
        Err(e) => {
            *attempt.failure.lock().unwrap() = Some(format!("failed to open microphone: {}", e));
            finish_worker(&mut env, state, &attempt);
            session_active.store(false, Ordering::SeqCst);
            return false;
        }
    };
    if let Err(e) = stream.play() {
        *attempt.failure.lock().unwrap() = Some(format!("failed to start microphone: {}", e));
        finish_worker(&mut env, state, &attempt);
        session_active.store(false, Ordering::SeqCst);
        return false;
    }
    state.stream = Some(SendStream(stream));
    notify_status(
        &mut env,
        state.target_ref.as_obj(),
        session_id,
        "Listening...",
    );
    if let Some(endpoint) = endpoint {
        let jvm = state.jvm.clone();
        let target_ref = state.target_ref.clone();
        let started_at = Instant::now();
        std::thread::spawn(move || loop {
            std::thread::sleep(Duration::from_millis(100));
            if !session_active.load(Ordering::SeqCst) {
                return;
            }
            let speech = endpoint.speech_started.load(Ordering::SeqCst);
            let silence = endpoint.last_voice.lock().unwrap().elapsed();
            let done = (speech && silence >= Duration::from_millis(AUTO_STOP_SILENCE_MS))
                || (!speech
                    && started_at.elapsed() >= Duration::from_millis(AUTO_STOP_NO_SPEECH_MS));
            if done && session_active.swap(false, Ordering::SeqCst) {
                if let Ok(mut env) = jvm.attach_current_thread() {
                    notify_auto_stop(&mut env, target_ref.as_obj(), session_id);
                }
                return;
            }
        });
    }
    true
}

pub fn stop_recording(mut env: JNIEnv, state: &mut VoiceSessionState, session_id: i64) -> bool {
    let attempt = match state.current.as_ref() {
        Some(a) if a.session_id == session_id => a.clone(),
        _ => return false,
    };
    if state.stream.is_none() {
        return false;
    }
    state.session_active.store(false, Ordering::SeqCst);
    state.stream = None;
    let (tail, total_pushed) = {
        let mut segmenter = state.segmenter.lock().unwrap();
        (segmenter.flush(), segmenter.total_pushed())
    };
    if total_pushed == 0 {
        *attempt.failure.lock().unwrap() = Some("no audio recorded".to_string());
    } else if let Some(piece) = tail {
        let sequence = state.next_sequence.fetch_add(1, Ordering::SeqCst);
        let job = PieceJob {
            sequence,
            samples: piece.samples,
            pause_before: piece.pause_before,
            cause: piece.cause,
            context: None,
        };
        match state.worker_tx.as_ref() {
            Some(tx) => {
                send_piece(tx, &attempt, job);
            }
            None => fail_piece(&attempt, job, "transcription worker stopped".to_string()),
        }
    }
    notify_status(
        &mut env,
        state.target_ref.as_obj(),
        session_id,
        "Transcribing...",
    );
    finish_worker(&mut env, state, &attempt);
    true
}

pub fn cancel_recording(mut env: JNIEnv, state: &mut VoiceSessionState, session_id: i64) -> bool {
    let attempt = match state.current.as_ref() {
        Some(a) if a.session_id == session_id => a.clone(),
        _ => return false,
    };
    state.session_active.store(false, Ordering::SeqCst);
    state.stream = None;
    state.worker_tx = None;
    state.segmenter.lock().unwrap().reset();
    attempt.cancelled.store(true, Ordering::SeqCst);
    attempt.processing.store(false, Ordering::SeqCst);
    attempt.retry_jobs.lock().unwrap().clear();
    *attempt.failure.lock().unwrap() = None;
    notify_status(&mut env, state.target_ref.as_obj(), session_id, "Canceled");
    notify_complete(
        &mut env,
        state.target_ref.as_obj(),
        &attempt,
        OUTCOME_CANCELLED,
        "canceled",
    );
    true
}

pub fn retry_recording(mut env: JNIEnv, state: &mut VoiceSessionState, session_id: i64) -> bool {
    let attempt = match state.current.as_ref() {
        Some(a) if a.session_id == session_id => a.clone(),
        _ => return false,
    };
    if attempt.processing.load(Ordering::SeqCst) {
        return false;
    }
    let jobs: Vec<_> = attempt.retry_jobs.lock().unwrap().drain(..).collect();
    if jobs.is_empty() {
        return false;
    }
    *attempt.failure.lock().unwrap() = None;
    attempt.terminal_sent.store(false, Ordering::SeqCst);
    attempt.processing.store(true, Ordering::SeqCst);
    let (tx, rx) = crossbeam_channel::unbounded();
    if !spawn_worker(
        state.jvm.clone(),
        state.target_ref.clone(),
        rx,
        attempt.clone(),
    ) {
        attempt.retry_jobs.lock().unwrap().extend(jobs);
        attempt.processing.store(false, Ordering::SeqCst);
        if attempt.failure.lock().unwrap().is_none() {
            *attempt.failure.lock().unwrap() = Some("transcription worker unavailable".to_string());
        }
        settle_attempt(&mut env, state.target_ref.as_obj(), &attempt);
        return false;
    }
    for job in jobs {
        send_piece(&tx, &attempt, job);
    }
    if tx.send(Job::End).is_err() {
        settle_attempt(&mut env, state.target_ref.as_obj(), &attempt);
        return false;
    }
    notify_status(
        &mut env,
        state.target_ref.as_obj(),
        session_id,
        "Retrying...",
    );
    true
}

pub fn cleanup_session(mut env: JNIEnv, state: &mut VoiceSessionState) {
    state.session_active.store(false, Ordering::SeqCst);
    state.stream = None;
    state.worker_tx = None;
    if let Some(attempt) = state.current.take() {
        if attempt.processing.swap(false, Ordering::SeqCst) {
            attempt.cancelled.store(true, Ordering::SeqCst);
            notify_complete(
                &mut env,
                state.target_ref.as_obj(),
                &attempt,
                OUTCOME_INTERRUPTED,
                "interrupted",
            );
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn piece(sequence: u64) -> PieceJob {
        PieceJob {
            sequence,
            samples: vec![sequence as f32],
            pause_before: sequence as f32,
            cause: CutCause::Silence,
            context: None,
        }
    }

    #[test]
    fn failed_piece_and_later_work_remain_in_order() {
        let attempt = Attempt::new(7);
        fail_piece(&attempt, piece(2), "x".into());
        attempt.retry_jobs.lock().unwrap().push_back(piece(3));
        let sequences: Vec<_> = attempt
            .retry_jobs
            .lock()
            .unwrap()
            .iter()
            .map(|p| p.sequence)
            .collect();
        assert_eq!(sequences, vec![2, 3]);
    }

    #[test]
    fn failed_enqueue_retains_the_exact_piece_for_retry() {
        let attempt = Attempt::new(7);
        let (tx, rx) = crossbeam_channel::unbounded();
        drop(rx);
        let original = piece(9);

        assert!(!send_piece(&tx, &attempt, original.clone()));

        let retry = attempt.retry_jobs.lock().unwrap();
        assert_eq!(retry.len(), 1);
        assert_eq!(retry[0].sequence, original.sequence);
        assert_eq!(retry[0].samples, original.samples);
        assert_eq!(retry[0].pause_before, original.pause_before);
        assert_eq!(retry[0].cause, original.cause);
        assert!(retry[0].context.is_none());
        assert_eq!(
            attempt.failure.lock().unwrap().as_deref(),
            Some("transcription worker stopped")
        );
    }

    #[test]
    fn final_context_keeps_original_suffix_after_normalized_prefix() {
        assert_eq!(
            suffix_after_prefix("hello world. Around with voiceovers.", "Hello, WORLD!"),
            Some("Around with voiceovers.".to_string())
        );
    }

    #[test]
    fn duplicate_word_at_final_boundary_is_not_dropped() {
        assert_eq!(
            suffix_after_prefix("we go, go now.", "We go."),
            Some("go now.".to_string())
        );
    }

    #[test]
    fn final_context_with_no_new_words_is_successful_empty_suffix() {
        assert_eq!(
            suffix_after_prefix("HELLO, world!", "Hello world."),
            Some(String::new())
        );
    }

    #[test]
    fn final_context_prefix_disagreement_requests_owned_tail_decode() {
        assert_eq!(
            suffix_after_prefix("yellow world around", "hello world"),
            None
        );
    }

    #[test]
    fn empty_owned_tail_is_an_explicit_disposition() {
        assert_eq!(owned_tail_text(" \n "), "");
        assert_eq!(
            owned_tail_text(" Around with voiceovers. "),
            "Around with voiceovers."
        );
    }

    #[test]
    fn split_pause_setting_accepts_only_the_published_range() {
        assert_eq!(parse_pause_seconds(" 3.0\n"), Some(3.0));
        assert_eq!(parse_pause_seconds("1.5"), Some(1.5));
        assert_eq!(parse_pause_seconds("8"), Some(8.0));
        assert_eq!(parse_pause_seconds("1.49"), None);
        assert_eq!(parse_pause_seconds("8.01"), None);
        assert_eq!(parse_pause_seconds("NaN"), None);
        assert_eq!(parse_pause_seconds("oops"), None);
    }
}
