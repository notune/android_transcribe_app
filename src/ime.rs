use jni::objects::{JClass, JObject};
use jni::JNIEnv;
use once_cell::sync::Lazy;
use std::sync::Mutex;

use crate::voice_session::{self, VoiceSessionState};

static IME_STATE: Lazy<Mutex<Option<VoiceSessionState>>> = Lazy::new(|| Mutex::new(None));

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_initNative(
    env: JNIEnv,
    _class: JClass,
    service: JObject,
) {
    let state = voice_session::init_session(env, service);
    *IME_STATE.lock().unwrap() = Some(state);
}

/// Only clears the session if it belongs to `service` (see recognize.rs).
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_cleanupNative(
    env: JNIEnv,
    _class: JClass,
    service: JObject,
) {
    let mut guard = IME_STATE.lock().unwrap();
    if guard
        .as_ref()
        .is_some_and(|s| env.is_same_object(&s.owner_ref, &service).unwrap_or(false))
    {
        *guard = None;
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_startRecording(
    env: JNIEnv,
    _class: JClass,
    callback: JObject,
) {
    let mut guard = IME_STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        state.target_ref = env.new_global_ref(callback).expect("recording callback");
        // The IME keyboard is manual tap-to-stop; no silence auto-stop.
        voice_session::start_recording(env, state, false);
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_stopRecording(
    env: JNIEnv,
    _class: JClass,
) {
    let mut guard = IME_STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        voice_session::stop_recording(env, state);
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_cancelRecording(
    env: JNIEnv,
    _class: JClass,
) {
    let mut guard = IME_STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        voice_session::cancel_recording(env, state);
    }
}
