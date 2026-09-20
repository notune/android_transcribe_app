use std::sync::Mutex;

use jni::objects::{JClass, JObject};
use jni::JNIEnv;
use once_cell::sync::Lazy;

use crate::voice_session::{self, VoiceSessionState};

static RECOG_STATE: Lazy<Mutex<Option<VoiceSessionState>>> = Lazy::new(|| Mutex::new(None));

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RecognizeActivity_initNative(
    env: JNIEnv,
    _class: JClass,
    activity: JObject,
) {
    let state = voice_session::init_session(env, activity);
    *RECOG_STATE.lock().unwrap() = Some(state);
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RecognizeActivity_cleanupNative(
    env: JNIEnv,
    _class: JClass,
) {
    let mut guard = RECOG_STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        voice_session::cleanup_session(env, state);
    }
    *guard = None;
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RecognizeActivity_startRecording(
    env: JNIEnv,
    _class: JClass,
    session_id: jni::sys::jlong,
    auto_stop: jni::sys::jboolean,
) -> jni::sys::jboolean {
    let mut guard = RECOG_STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        return voice_session::start_recording(env, state, session_id, auto_stop != 0)
            as jni::sys::jboolean;
    }
    0
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RecognizeActivity_stopRecording(
    env: JNIEnv,
    _class: JClass,
    session_id: jni::sys::jlong,
) -> jni::sys::jboolean {
    let mut guard = RECOG_STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        return voice_session::stop_recording(env, state, session_id) as jni::sys::jboolean;
    }
    0
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RecognizeActivity_cancelRecording(
    env: JNIEnv,
    _class: JClass,
    session_id: jni::sys::jlong,
) -> jni::sys::jboolean {
    let mut guard = RECOG_STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        return voice_session::cancel_recording(env, state, session_id) as jni::sys::jboolean;
    }
    0
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RecognizeActivity_retryRecording(
    env: JNIEnv,
    _class: JClass,
    session_id: jni::sys::jlong,
) -> jni::sys::jboolean {
    let mut guard = RECOG_STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        return voice_session::retry_recording(env, state, session_id) as jni::sys::jboolean;
    }
    0
}
