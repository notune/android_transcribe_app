use jni::objects::{JClass, JObject};
use jni::sys::jboolean;
use jni::JNIEnv;
use once_cell::sync::Lazy;
use std::sync::{Arc, Mutex};

use crate::engine;
use crate::voice_session::{self, VoiceSessionState};

static BUBBLE_STATE: Lazy<Mutex<Option<VoiceSessionState>>> = Lazy::new(|| Mutex::new(None));

/// Creates the Flow bubble's voice session without loading the model. The
/// target (the accessibility service) receives onStatusUpdate / onAudioLevel /
/// onTextTranscribed and doubles as the Context the engine loads from.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_BubbleRecorder_initNative(
    env: JNIEnv,
    _class: JClass,
    target: JObject,
) {
    let state = voice_session::init_session_lazy(env, target);
    *BUBBLE_STATE.lock().unwrap() = Some(state);
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_BubbleRecorder_cleanupNative(
    _env: JNIEnv,
    _class: JClass,
) {
    *BUBBLE_STATE.lock().unwrap() = None;
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_BubbleRecorder_startRecordingNative(
    env: JNIEnv,
    _class: JClass,
) {
    let mut guard = BUBBLE_STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        voice_session::start_recording(env, state, false);
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_BubbleRecorder_stopRecordingNative(
    env: JNIEnv,
    _class: JClass,
) {
    let mut guard = BUBBLE_STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        voice_session::stop_recording(env, state);
    }
}

/// Stops capture and discards the audio; nothing is transcribed.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_BubbleRecorder_cancelRecordingNative(
    env: JNIEnv,
    _class: JClass,
) {
    let mut guard = BUBBLE_STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        voice_session::cancel_recording(env, state);
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_BubbleRecorder_isEngineLoadedNative(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    engine::is_engine_loaded() as jboolean
}

/// Battery-saver unload: frees the shared model only when no component holds a
/// reference (see `engine::unload_if_idle`). The session is kept, so the next
/// recording reloads. Returns whether the model was unloaded (or already
/// absent); `false` means another transcription is in flight.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_BubbleRecorder_unloadNative(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    engine::unload_if_idle() as jboolean
}

/// Loads the shared engine, blocking until it is ready or fails. Call from a
/// background thread. Status updates go to `sink` (a Context), not to the
/// session target, so a warm-up failure never reads as a recording error.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_BubbleRecorder_loadEngineNative(
    env: JNIEnv,
    _class: JClass,
    sink: JObject,
) -> jboolean {
    let jvm = match env.get_java_vm() {
        Ok(vm) => Arc::new(vm),
        Err(_) => return 0,
    };
    let sink_ref = match env.new_global_ref(&sink) {
        Ok(r) => r,
        Err(_) => return 0,
    };
    engine::ensure_loaded_from_thread(&jvm, &sink_ref).is_ok() as jboolean
}
