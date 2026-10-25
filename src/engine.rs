//! Loads and serves the speech-to-text engine (transcribe.cpp, GGUF models).
//!
//! The engine is a process-wide singleton behind `Arc<Mutex<..>>`: a
//! transcribe.cpp session may only be used by one thread at a time, which the
//! mutex guarantees. Model selection is read from marker files in filesDir
//! (written by `ModelsActivity`): an imported GGUF if one is selected, the
//! bundled model otherwise — with the bundled model as fallback if the
//! imported one fails to load.

use once_cell::sync::Lazy;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::time::SystemTime;

use jni::objects::{GlobalRef, JObject};
use jni::JNIEnv;

use crate::assets;

/// File in filesDir naming the imported GGUF (a file under `models/`) to use
/// instead of the bundled model. Absent or empty = bundled model.
const ACTIVE_MODEL_FILE: &str = "active_model";
/// File in filesDir with an optional language hint — a locale like `en-US`
/// or `auto`. Absent or empty = let the model autodetect.
const MODEL_LANGUAGE_FILE: &str = "model_language";
/// Marker file in filesDir: when present, models that support translation
/// (e.g. Whisper) translate speech to English instead of transcribing it.
/// Ignored by models without translation support.
const MODEL_TRANSLATE_FILE: &str = "model_translate";
/// Optional file in filesDir with the CPU thread count for inference.
/// Absent/invalid/0 = default (all cores).
const MODEL_THREADS_FILE: &str = "model_threads";

/// Longest audio passed to the model in one run (60 s). Offline conformer
/// models use full self-attention, whose cost grows quadratically with input
/// length — an unbounded shared audio file would exhaust memory on a phone.
/// Longer input is split at quiet points and the texts joined.
const MAX_RUN_SAMPLES: usize = 60 * 16_000;
/// When splitting, search this far back from the hard boundary for the
/// quietest point so words aren't cut mid-syllable.
const SPLIT_SEARCH_SAMPLES: usize = 10 * 16_000;

/// Length of the next piece of `samples` to transcribe in one model run:
/// everything if it fits, otherwise up to the quietest point near the limit.
pub fn next_run_len(samples: &[f32]) -> usize {
    if samples.len() <= MAX_RUN_SAMPLES {
        samples.len()
    } else {
        crate::audio::find_quietest_split(
            samples,
            MAX_RUN_SAMPLES - SPLIT_SEARCH_SAMPLES,
            MAX_RUN_SAMPLES,
        )
    }
}

/// True if `samples` is long enough that [`next_run_len`] would split it.
pub fn exceeds_one_run(samples: &[f32]) -> bool {
    samples.len() > MAX_RUN_SAMPLES
}

/// A loaded transcribe.cpp session plus the options applied to every run.
pub struct Engine {
    session: transcribe_cpp::Session,
    language: Option<String>,
    task: transcribe_cpp::Task,
    /// Family-specific decode options attached to every run; `None` for
    /// models that don't take the whisper run extension.
    run_ext: Option<transcribe_cpp::RunExtension>,
    /// Status reported once loading succeeded; carries a warning when the
    /// translate setting can't do what the user expects with this model.
    ready_status: &'static str,
    /// The model decodes incrementally (transcribe.cpp's streaming API), so
    /// voice input can show text while the user speaks (see live.rs).
    supports_streaming: bool,
    /// A stream already began with the current language hint, so beginning
    /// another needs no check for a rejected hint (see `begin_stream`).
    stream_language_ok: bool,
}

impl Engine {
    fn load(
        model_path: &Path,
        language: Option<String>,
        translate: bool,
        threads: i32,
    ) -> Result<Engine, String> {
        if !model_path.is_file() {
            return Err(format!("model file not found: {}", model_path.display()));
        }
        let model = transcribe_cpp::Model::load(model_path).map_err(|e| e.to_string())?;
        // Translation is gated on the model's capabilities: models without it
        // (e.g. Parakeet) silently keep transcribing, so the setting can stay
        // on while switching models.
        let capabilities = model.capabilities().ok();
        let supports_translate = capabilities.as_ref().is_some_and(|c| c.supports_translate);
        let supports_streaming = capabilities.as_ref().is_some_and(|c| c.supports_streaming);
        let task = if translate && supports_translate {
            transcribe_cpp::Task::Translate
        } else {
            if translate {
                log::info!("translate requested but unsupported by this model; transcribing");
            }
            transcribe_cpp::Task::Transcribe
        };
        // Silently doing something other than what the translate switch says
        // looks like a bug (still-untranslated subtitles), so say it in the
        // status. Whisper Turbo is special-cased: it advertises translation
        // but was distilled without translation data — verified on-device to
        // keep transcribing German as German with task=Translate.
        let ready_status = if translate && task == transcribe_cpp::Task::Transcribe {
            "Ready (this model can't translate)"
        } else if translate && model.variant().contains("turbo") {
            "Ready (note: Whisper Turbo translates poorly; use another Whisper)"
        } else {
            "Ready"
        };
        // Whisper's stock recipe re-decodes a chunk at up to five higher
        // temperatures when its quality gates fail, so one noisy chunk can
        // cost several full decodes. For an interactive app a single greedy
        // pass is the better trade: worst case is a worse line of text, not
        // a multiplied wait. temperature_inc = 0 turns the retry ladder off;
        // models that don't take the whisper run extension are unaffected.
        let run_ext = if model.accepts_ext(
            transcribe_cpp::ExtSlot::Run,
            transcribe_cpp::sys::TRANSCRIBE_EXT_KIND_WHISPER_RUN,
        ) {
            Some(transcribe_cpp::RunExtension::Whisper(
                transcribe_cpp::WhisperRunOptions {
                    temperature_inc: Some(0.0),
                    ..Default::default()
                },
            ))
        } else {
            None
        };

        log::info!(
            "engine: {} threads, task {:?}, single-pass decode: {}, streaming: {}",
            threads,
            task,
            run_ext.is_some(),
            supports_streaming
        );
        let options = transcribe_cpp::SessionOptions {
            n_threads: threads,
            ..Default::default()
        };
        let session = model.session_with(&options).map_err(|e| e.to_string())?;
        Ok(Engine {
            session,
            language,
            task,
            run_ext,
            ready_status,
            supports_streaming,
            stream_language_ok: false,
        })
    }

    pub fn supports_streaming(&self) -> bool {
        self.supports_streaming
    }

    /// Begins a stream with the same options as a run. A rejected language
    /// hint is degraded as in [`Engine::run`]; the hint is checked with a
    /// throwaway stream first, because a stream borrowed from inside the
    /// retry loop could not be returned from it.
    pub fn begin_stream(&mut self) -> Result<transcribe_cpp::Stream<'_>, String> {
        let stream_options = transcribe_cpp::StreamOptions::default();
        while !self.stream_language_ok {
            let run_options = self.run_options();
            match self.session.stream(&run_options, &stream_options).map(drop) {
                Ok(()) => self.stream_language_ok = true,
                Err(transcribe_cpp::Error::Unsupported(msg)) if self.language.is_some() => {
                    self.degrade_language(&msg)
                }
                Err(e) => return Err(e.to_string()),
            }
        }
        let run_options = self.run_options();
        self.session
            .stream(&run_options, &stream_options)
            .map_err(|e| e.to_string())
    }

    fn run_options(&self) -> transcribe_cpp::RunOptions {
        transcribe_cpp::RunOptions {
            language: self.language.clone(),
            task: self.task,
            family: self.run_ext.clone(),
            ..Default::default()
        }
    }

    /// Drops the region from a rejected language hint (`de-DE` -> `de`), or
    /// the hint altogether if it has none.
    fn degrade_language(&mut self, msg: &str) {
        let lang = self.language.take().unwrap_or_default();
        self.language = lang.split_once('-').map(|(primary, _)| primary.to_string());
        self.stream_language_ok = false;
        log::warn!(
            "language hint '{}' rejected ({}); retrying with {:?}",
            lang,
            msg,
            self.language
        );
    }

    /// Transcribes 16 kHz mono f32 samples to text. Input longer than
    /// [`MAX_RUN_SAMPLES`] is transcribed in quiet-point chunks.
    pub fn transcribe(&mut self, samples: Vec<f32>) -> Result<String, String> {
        if samples.len() <= MAX_RUN_SAMPLES {
            return self.run(&samples);
        }

        let mut text = String::new();
        let mut rest: &[f32] = &samples;
        while !rest.is_empty() {
            let take = next_run_len(rest);
            let piece = self.run(&rest[..take])?;
            let piece = piece.trim();
            if !piece.is_empty() {
                if !text.is_empty() {
                    text.push(' ');
                }
                text.push_str(piece);
            }
            rest = &rest[take..];
        }
        Ok(text)
    }

    /// One model run. A rejected language hint is degraded instead of
    /// failing the transcription: `de-DE` retries as `de`, then as no hint
    /// (each model knows a different set of tags — e.g. Parakeet v3 takes
    /// locales/short codes, English-only models take none). The degraded
    /// value is kept so later runs skip the rejected attempts.
    fn run(&mut self, samples: &[f32]) -> Result<String, String> {
        loop {
            let opts = self.run_options();
            match self.session.run(samples, &opts) {
                Ok(t) => return Ok(t.text),
                // Some model families stop a decode that hit its length
                // budget or started repeating itself; the text up to that
                // point is still the best answer, so keep it.
                Err(transcribe_cpp::Error::OutputTruncated { message, partial })
                | Err(transcribe_cpp::Error::OutputRepetition { message, partial }) => {
                    log::warn!("decode stopped early ({}); using the partial text", message);
                    return Ok(partial.map(|t| t.text).unwrap_or_default());
                }
                Err(transcribe_cpp::Error::Unsupported(msg)) if self.language.is_some() => {
                    self.degrade_language(&msg)
                }
                Err(e) => return Err(e.to_string()),
            }
        }
    }
}

/// Holds the loaded engine singleton.
static GLOBAL_ENGINE: Lazy<Mutex<Option<Arc<Mutex<Engine>>>>> =
    Lazy::new(|| Mutex::new(None));

/// The settings the loaded engine was built from, and where they live.
/// Comparing against the files on disk before transcribing makes any user of
/// the engine pick up a changed model or language, whichever screen changed
/// it, instead of keeping the old one until the process dies.
static LOADED_CONFIG: Lazy<Mutex<Option<(PathBuf, EngineConfig)>>> =
    Lazy::new(|| Mutex::new(None));

#[derive(Debug, Clone, PartialEq)]
struct EngineConfig {
    active_model: Option<String>,
    /// Size and mtime of the imported model file, so re-importing a file
    /// under the same name also reloads.
    model_stamp: Option<(u64, Option<SystemTime>)>,
    language: Option<String>,
    translate: bool,
    threads: Option<i32>,
}

impl EngineConfig {
    fn read(files_dir: &Path) -> EngineConfig {
        let active_model = read_config(&files_dir.join(ACTIVE_MODEL_FILE));
        let model_stamp = active_model.as_ref().and_then(|name| {
            let meta = std::fs::metadata(files_dir.join("models").join(name)).ok()?;
            Some((meta.len(), meta.modified().ok()))
        });
        EngineConfig {
            active_model,
            model_stamp,
            // Absent/empty or "auto" = no hint (the model's automatic mode).
            // A tag the model doesn't know is degraded per run (Engine::run).
            language: read_config(&files_dir.join(MODEL_LANGUAGE_FILE))
                .filter(|l| !l.eq_ignore_ascii_case("auto")),
            translate: files_dir.join(MODEL_TRANSLATE_FILE).exists(),
            threads: read_config(&files_dir.join(MODEL_THREADS_FILE))
                .and_then(|s| s.parse::<i32>().ok())
                .filter(|&n| n > 0),
        }
    }
}

/// True if the model settings changed since the engine was loaded.
fn config_is_stale() -> bool {
    match &*LOADED_CONFIG.lock().unwrap() {
        Some((dir, loaded)) => EngineConfig::read(dir) != *loaded,
        None => false,
    }
}

/// Drops the engine if its settings are outdated, so the next load picks up
/// the current ones. Re-checked under the load lock, so concurrent callers
/// don't throw away a load that already uses the new settings.
fn reset_if_stale() {
    let (lock, cvar) = &*LOAD_STATE;
    let mut state = lock.lock().unwrap();
    while *state == LoadState::Loading {
        state = cvar.wait(state).unwrap();
    }
    if config_is_stale() {
        log::info!("model settings changed; reloading the engine");
        *GLOBAL_ENGINE.lock().unwrap() = None;
        *state = LoadState::Idle;
    }
}

/// True if the engine is loaded and matches the current settings.
pub fn is_current() -> bool {
    is_engine_loaded() && !config_is_stale()
}

/// Loading coordination state + condvar for waiters.
static LOAD_STATE: Lazy<(Mutex<LoadState>, Condvar)> =
    Lazy::new(|| (Mutex::new(LoadState::Idle), Condvar::new()));

#[derive(Debug, Clone, PartialEq)]
enum LoadState {
    /// No load in progress
    Idle,
    /// A thread is currently loading the model
    Loading,
    /// Loading completed successfully
    Done,
    /// Loading failed
    Failed(String),
}

pub fn get_engine() -> Option<Arc<Mutex<Engine>>> {
    GLOBAL_ENGINE.lock().unwrap().clone()
}

/// Whether the model last loaded supports streaming, readable without
/// waiting for the engine lock (a subtitle run may hold it).
static LOADED_STREAMING: AtomicBool = AtomicBool::new(false);

/// True if the loaded engine's model supports streaming (see live.rs).
pub fn engine_supports_streaming() -> bool {
    is_engine_loaded() && LOADED_STREAMING.load(Ordering::SeqCst)
}

fn install_engine(engine: Engine, files_dir: PathBuf, config: EngineConfig) {
    LOADED_STREAMING.store(engine.supports_streaming, Ordering::SeqCst);
    *GLOBAL_ENGINE.lock().unwrap() = Some(Arc::new(Mutex::new(engine)));
    *LOADED_CONFIG.lock().unwrap() = Some((files_dir, config));
}

/// Number of voice-input transcriptions (keyboard, popup, speech service,
/// shared files) waiting for or using the engine. All features share one
/// engine, which runs one transcription at a time, so live subtitles yield to
/// these: a subtitle job doesn't start while one is pending, and voice input
/// waits for at most the subtitle run already in flight.
static INTERACTIVE: Lazy<(Mutex<usize>, Condvar)> = Lazy::new(|| (Mutex::new(0), Condvar::new()));

pub(crate) struct InteractiveTurn;

impl InteractiveTurn {
    pub(crate) fn begin() -> InteractiveTurn {
        *INTERACTIVE.0.lock().unwrap() += 1;
        InteractiveTurn
    }
}

impl Drop for InteractiveTurn {
    fn drop(&mut self) {
        let (lock, cvar) = &*INTERACTIVE;
        let mut pending = lock.lock().unwrap();
        *pending -= 1;
        if *pending == 0 {
            cvar.notify_all();
        }
    }
}

/// Transcribes for someone waiting on the result (voice input, a shared
/// file, the benchmark): takes precedence over live subtitles.
pub fn transcribe_shared(engine: &Arc<Mutex<Engine>>, samples: Vec<f32>) -> Result<String, String> {
    crate::live::finish_active();
    let _turn = InteractiveTurn::begin();
    transcribe_guarded(engine, samples)
}

/// [`transcribe_shared`] for a recording or file: long quiet stretches
/// before and after the speech are left out (see
/// [`crate::endpoint::speech_range`]).
pub fn transcribe_speech(engine: &Arc<Mutex<Engine>>, samples: Vec<f32>) -> Result<String, String> {
    let speech = crate::endpoint::speech_range(&samples);
    if speech.is_empty() {
        log::info!("only digital silence recorded; nothing to transcribe");
        return Ok(String::new());
    }
    let samples = if speech.len() < samples.len() {
        log::info!(
            "transcribing {:.1}-{:.1}s of {:.1}s (quiet start/end left out)",
            speech.start as f64 / 16_000.0,
            speech.end as f64 / 16_000.0,
            samples.len() as f64 / 16_000.0
        );
        samples[speech].to_vec()
    } else {
        samples
    };
    transcribe_shared(engine, samples)
}

/// Transcribes live subtitles: waits while voice input needs the engine.
pub fn transcribe_background(
    engine: &Arc<Mutex<Engine>>,
    samples: Vec<f32>,
) -> Result<String, String> {
    {
        let (lock, cvar) = &*INTERACTIVE;
        let mut pending = lock.lock().unwrap();
        while *pending > 0 {
            pending = cvar.wait(pending).unwrap();
        }
    }
    transcribe_guarded(engine, samples)
}

/// Runs a transcription on the shared engine. Two layers of hardening keep a
/// single bad run from freezing every later one (the symptom would be an IME
/// stuck at "Processing" until its process dies, since notify callbacks stop
/// coming): a panic anywhere in the engine stack is caught and surfaced as a
/// normal error, and a lock poisoned by an earlier panic is recovered instead
/// of propagating the poison forever.
fn transcribe_guarded(engine: &Arc<Mutex<Engine>>, samples: Vec<f32>) -> Result<String, String> {
    let audio_secs = samples.len() as f64 / 16_000.0;
    let started = std::time::Instant::now();
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let mut guard = engine.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        guard.transcribe(samples)
    }))
    .unwrap_or_else(|_| {
        log::error!("transcription panicked; reporting as error");
        Err("transcription failed unexpectedly, please try again".to_string())
    });
    log::info!(
        "transcribed {:.1}s audio in {:.2}s",
        audio_secs,
        started.elapsed().as_secs_f64()
    );
    result
}

pub fn is_engine_loaded() -> bool {
    GLOBAL_ENGINE.lock().unwrap().is_some()
}

/// Drops the loaded engine and clears the load state so the next
/// `ensure_loaded*` call reloads with the current model selection. Waits for
/// an in-flight load to finish first, so a reload can't race a load of the
/// previous selection. Call from a background thread.
pub fn reset() {
    crate::live::finish_active();
    // Reset runs off the UI thread. Wait for the old stream to release its
    // compute lease before permitting a second full model allocation.
    if let Some(engine) = get_engine() {
        drop(engine.lock().unwrap_or_else(|p| p.into_inner()));
    }
    let (lock, cvar) = &*LOAD_STATE;
    let mut state = lock.lock().unwrap();
    while *state == LoadState::Loading {
        state = cvar.wait(state).unwrap();
    }
    *GLOBAL_ENGINE.lock().unwrap() = None;
    *state = LoadState::Idle;
}

fn notify_status(env: &mut JNIEnv, obj: &JObject, msg: &str) {
    if let Ok(jmsg) = env.new_string(msg) {
        let _ = env.call_method(
            obj,
            "onStatusUpdate",
            "(Ljava/lang/String;)V",
            &[(&jmsg).into()],
        );
    }
}

/// Ensures the engine is loaded. Safe to call from multiple threads
/// concurrently; reports status via the target's `onStatusUpdate` callback.
///
/// Convenience wrapper around [`ensure_loaded_from_thread`] for JNI entry
/// points that already hold an attached `JNIEnv`.
pub fn ensure_loaded(env: &mut JNIEnv, context: &JObject) -> Result<(), String> {
    let jvm = Arc::new(env.get_java_vm().map_err(|e| e.to_string())?);
    let context_ref = env.new_global_ref(context).map_err(|e| e.to_string())?;
    ensure_loaded_from_thread(&jvm, &context_ref)
}

/// Ensures the engine is loaded. Safe to call from multiple threads concurrently.
///
/// - If already loaded, returns immediately.
/// - If another thread is loading, waits for it to finish.
/// - If no one is loading, this thread takes ownership of loading.
/// - If a previous load failed, retries.
///
/// Reports status via the target's `onStatusUpdate` JNI callback.
pub fn ensure_loaded_from_thread(
    jvm: &Arc<jni::JavaVM>,
    target_ref: &GlobalRef,
) -> Result<(), String> {
    ensure_loaded_reporting(jvm, target_ref, true)
}

/// [`ensure_loaded_from_thread`] for use during a recording: waiting for
/// another thread's load (or finding the engine already loaded) is not
/// reported, since a "Waiting"/"Ready" status there would make the keyboard
/// disable its stop button or reset its display. A load this thread performs
/// still reports its progress and errors.
pub fn ensure_loaded_quietly(
    jvm: &Arc<jni::JavaVM>,
    target_ref: &GlobalRef,
) -> Result<(), String> {
    ensure_loaded_reporting(jvm, target_ref, false)
}

fn ensure_loaded_reporting(
    jvm: &Arc<jni::JavaVM>,
    target_ref: &GlobalRef,
    report_waits: bool,
) -> Result<(), String> {
    let notify = |msg: &str| {
        if !report_waits && !msg.starts_with("Error") {
            return;
        }
        if let Ok(mut env) = jvm.attach_current_thread() {
            notify_status(&mut env, target_ref.as_obj(), msg);
        }
    };

    if config_is_stale() {
        reset_if_stale();
    }

    // Fast path: already loaded
    if is_engine_loaded() {
        notify("Ready");
        return Ok(());
    }

    let (lock, cvar) = &*LOAD_STATE;
    let mut state = lock.lock().unwrap();

    // Re-check under lock
    if is_engine_loaded() {
        notify("Ready");
        return Ok(());
    }

    match &*state {
        LoadState::Loading => {
            // Another thread is loading — wait for it
            notify("Waiting for model...");
            while *state == LoadState::Loading {
                state = cvar.wait(state).unwrap();
            }
            drop(state);

            if is_engine_loaded() {
                notify("Ready");
                Ok(())
            } else {
                let msg = "Model failed to load".to_string();
                notify(&format!("Error: {}", msg));
                Err(msg)
            }
        }
        LoadState::Done => {
            notify("Ready");
            Ok(())
        }
        LoadState::Idle | LoadState::Failed(_) => {
            // We take ownership of loading (retry on previous failure)
            *state = LoadState::Loading;
            drop(state);

            let result = if let Ok(mut env) = jvm.attach_current_thread() {
                do_load(&mut env, target_ref.as_obj())
            } else {
                Err("Failed to attach JNI thread".to_string())
            };

            let mut state = lock.lock().unwrap();
            match &result {
                Ok(()) => *state = LoadState::Done,
                Err(msg) => *state = LoadState::Failed(msg.clone()),
            }
            cvar.notify_all();
            result
        }
    }
}

/// Inference thread count: the number of performance cores, capped at 4.
///
/// Phone SoCs are heterogeneous: fast cores paired with slow efficiency
/// cores. ggml synchronizes all threads after each operation, so a thread
/// on a slow core stalls the whole pool, and using every core is slower
/// than using only the fast ones. Cores are classified by their maximum
/// frequency from sysfs: within 70% of the fastest core counts as fast.
/// The count is capped at 4 because grabbing every fast core leaves none
/// for the rest of the system (audio pipeline, the app playing the sound),
/// and any preempted worker stalls the pool at the next op barrier; past
/// 4 threads the matmuls are memory-bound on phone-class SoCs anyway, and
/// more threads mainly build up heat. If sysfs is unreadable, falls back
/// to a conservative 4. The `model_threads` config file (user-settable in
/// the Models screen) overrides the heuristic.
fn performance_core_count() -> i32 {
    let mut freqs: Vec<u64> = Vec::new();
    for i in 0..64 {
        let path = format!("/sys/devices/system/cpu/cpu{i}/cpufreq/cpuinfo_max_freq");
        match std::fs::read_to_string(&path) {
            Ok(s) => match s.trim().parse::<u64>() {
                Ok(f) => freqs.push(f),
                Err(_) => break,
            },
            Err(_) => break,
        }
    }
    match freqs.iter().max() {
        Some(&max) if max > 0 => {
            let fast = freqs.iter().filter(|&&f| f * 10 >= max * 7).count();
            let threads = fast.clamp(1, 4);
            log::info!(
                "cpu clusters {:?} kHz -> {} performance cores -> {} threads",
                freqs,
                fast,
                threads
            );
            threads as i32
        }
        _ => std::thread::available_parallelism()
            .map(|n| n.get())
            .unwrap_or(4)
            .min(4) as i32,
    }
}

/// CPU features this build's ggml kernels require (see GGML_CPU_ARM_ARCH in
/// the gradle build): dot-product and half-precision SIMD, present on arm64
/// cores since ~2018. Without this check, an older CPU would crash with an
/// illegal instruction mid-inference instead of showing an error.
#[cfg(target_arch = "aarch64")]
fn check_cpu_features() -> Result<(), String> {
    const HWCAP_ASIMHP: libc::c_ulong = 1 << 10; // FEAT_FP16 (asimdhp)
    const HWCAP_ASIMDDP: libc::c_ulong = 1 << 20; // FEAT_DotProd (asimddp)
    let hwcap = unsafe { libc::getauxval(libc::AT_HWCAP) };
    if hwcap & HWCAP_ASIMDDP == 0 || hwcap & HWCAP_ASIMHP == 0 {
        return Err(
            "this device's CPU is too old for this app version (needs arm64 \
             dotprod/fp16, available on phones from ~2018 on)"
                .to_string(),
        );
    }
    Ok(())
}

#[cfg(not(target_arch = "aarch64"))]
fn check_cpu_features() -> Result<(), String> {
    Ok(())
}

/// Reads a single-value config file (trimmed); `None` if absent or empty.
fn read_config(path: &Path) -> Option<String> {
    let s = std::fs::read_to_string(path).ok()?;
    let s = s.trim();
    if s.is_empty() {
        None
    } else {
        Some(s.to_string())
    }
}

/// Status reported when there is nothing to load: no imported model is
/// selected and this build bundles none (F-Droid). The app matches this exact
/// text (`ModelStore.NO_MODEL_STATUS`) to show its translated hint instead.
const NO_MODEL_STATUS: &str = "Error: no speech model installed";

/// Performs the model load: the selected imported GGUF if any (falling back
/// to the bundled model on failure), otherwise the bundled model.
fn do_load(env: &mut JNIEnv, context: &JObject) -> Result<(), String> {
    if let Err(msg) = check_cpu_features() {
        notify_status(env, context, &format!("Error: {}", msg));
        return Err(msg);
    }

    let files_dir = assets::files_dir(env, context).map_err(|e| {
        let msg = format!("Failed to resolve filesDir: {}", e);
        notify_status(env, context, &format!("Error: {}", msg));
        msg
    })?;
    let config = EngineConfig::read(&files_dir);
    let language = config.language.clone();
    let translate = config.translate;
    let threads = config.threads.unwrap_or_else(performance_core_count);
    let has_builtin = assets::has_builtin_model(env, context);

    if let Some(name) = config.active_model.clone() {
        let path = files_dir.join("models").join(&name);
        notify_status(env, context, &format!("Loading model {}...", name));
        match Engine::load(&path, language.clone(), translate, threads) {
            Ok(engine) => {
                let status = engine.ready_status;
                install_engine(engine, files_dir, config);
                notify_status(env, context, status);
                return Ok(());
            }
            Err(e) => {
                log::error!("Imported model {} failed to load: {}", path.display(), e);
                if !has_builtin {
                    let msg = format!("Error loading {}: {}", name, e);
                    notify_status(env, context, &msg);
                    return Err(msg);
                }
                notify_status(
                    env,
                    context,
                    &format!("Error loading {}: {} — using built-in model", name, e),
                );
                // fall through to the bundled model
            }
        }
    }

    if !has_builtin {
        notify_status(env, context, NO_MODEL_STATUS);
        return Err(NO_MODEL_STATUS.trim_start_matches("Error: ").to_string());
    }

    notify_status(env, context, "Checking assets...");

    let path = assets::extract_builtin_model(env, context).map_err(|e| {
        let msg = format!("Asset error: {}", e);
        notify_status(env, context, &format!("Error: {}", msg));
        msg
    })?;

    notify_status(env, context, "Loading model...");

    match Engine::load(&path, language, translate, threads) {
        Ok(engine) => {
            let status = engine.ready_status;
            install_engine(engine, files_dir, config);
            notify_status(env, context, status);
            Ok(())
        }
        Err(e) => {
            // Load failed — likely corrupt/incomplete extraction. Invalidate
            // it so the next attempt re-extracts from the APK.
            assets::invalidate_builtin_model(&path);
            let msg = format!("Model error: {}", e);
            notify_status(env, context, &format!("Error: {}", msg));
            Err(msg)
        }
    }
}
