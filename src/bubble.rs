use jni::objects::{JClass, JObject};
use jni::sys::jboolean;
use jni::JNIEnv;
use once_cell::sync::Lazy;
use std::sync::Mutex;

use crate::engine;
use crate::voice_session::{self, VoiceSessionState};

static BUBBLE_STATE: Lazy<Mutex<Option<VoiceSessionState>>> = Lazy::new(|| Mutex::new(None));

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_BubbleService_initNative(
    env: JNIEnv,
    _class: JClass,
    service: JObject,
) {
    let state = voice_session::init_session(env, service);
    *BUBBLE_STATE.lock().unwrap() = Some(state);
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_BubbleService_cleanupNative(
    _env: JNIEnv,
    _class: JClass,
) {
    *BUBBLE_STATE.lock().unwrap() = None;
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_BubbleService_startRecordingNative(
    env: JNIEnv,
    _class: JClass,
) {
    let mut guard = BUBBLE_STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        voice_session::start_recording(env, state, false);
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_BubbleService_stopRecordingNative(
    env: JNIEnv,
    _class: JClass,
) {
    let mut guard = BUBBLE_STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        voice_session::stop_recording(env, state);
    }
}

/// Battery-saver unload: frees the shared model only when no component holds a
/// reference (see `engine::unload_if_idle`). The bubble's own session
/// (`BUBBLE_STATE`) is kept — only the heavy model is dropped, so the overlay
/// stays live and the next tap reloads. Returns whether the model was unloaded
/// (or already absent); `false` means another transcription is in flight and
/// the caller should retry later.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_BubbleService_unloadNative(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    engine::unload_if_idle() as jboolean
}

/// Reloads the shared engine on a caller-owned background thread, blocking
/// until it is ready or fails. Clones the session's JVM/target refs out of
/// `BUBBLE_STATE` and releases the lock before loading so a slow load never
/// blocks recording start/stop. Returns `false` when there is no session
/// (never start a recording then) or the load failed.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_BubbleService_ensureEngineNative(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let parts = {
        let guard = BUBBLE_STATE.lock().unwrap();
        guard
            .as_ref()
            .map(|state| (state.jvm.clone(), state.target_ref.clone()))
    };
    match parts {
        Some((jvm, target_ref)) => {
            engine::ensure_loaded_from_thread(&jvm, &target_ref).is_ok() as jboolean
        }
        None => 0 as jboolean,
    }
}
