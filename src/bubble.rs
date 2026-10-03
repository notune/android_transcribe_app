use jni::objects::{JClass, JObject};
use jni::JNIEnv;
use once_cell::sync::Lazy;
use std::sync::Mutex;

use crate::voice_session::{self, VoiceSessionState};

// Dedicated session state for the accessibility bubble service (separate from
// the IME and the RecognizeActivity so all three can coexist).
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
pub unsafe extern "system" fn Java_dev_notune_transcribe_BubbleService_startRecording(
    env: JNIEnv,
    _class: JClass,
) {
    let mut guard = BUBBLE_STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        // Tap-to-stop, no auto-stop ( Wispr Flow behaviour: user taps again).
        voice_session::start_recording(env, state, false);
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_BubbleService_stopRecording(
    env: JNIEnv,
    _class: JClass,
) {
    let mut guard = BUBBLE_STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        voice_session::stop_recording(env, state);
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_BubbleService_cancelRecording(
    env: JNIEnv,
    _class: JClass,
) {
    let mut guard = BUBBLE_STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        voice_session::cancel_recording(env, state);
    }
}
