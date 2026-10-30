//! JNI delivery shared by capture, streaming and model-loading voice paths.
use jni::{objects::JObject, JNIEnv};

fn string_callback(env: &mut JNIEnv, target: &JObject, method: &str, text: &str) {
    if let Ok(value) = env.new_string(text) {
        let _ = env.call_method(target, method, "(Ljava/lang/String;)V", &[(&value).into()]);
        clear_exception(env);
        let _ = env.delete_local_ref(value);
    } else {
        clear_exception(env);
    }
}
pub fn status(env: &mut JNIEnv, target: &JObject, text: &str) {
    string_callback(env, target, "onStatusUpdate", text);
}
pub fn text(env: &mut JNIEnv, target: &JObject, text: &str) {
    string_callback(env, target, "onTextTranscribed", text);
}
pub fn failure(env: &mut JNIEnv, target: &JObject, error: &str) {
    string_callback(env, target, "onCaptureFailed", error);
}
pub fn level(env: &mut JNIEnv, target: &JObject, value: f32) {
    let _ = env.call_method(target, "onAudioLevel", "(F)V", &[value.into()]);
    clear_exception(env);
}
fn clear_exception(env: &mut JNIEnv) {
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_describe();
        let _ = env.exception_clear();
    }
}
