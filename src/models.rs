use crate::engine;
use jni::objects::{JClass, JObject};
use jni::JNIEnv;
use std::sync::Arc;

/// Drops the current engine and reloads it on a background thread, reporting
/// progress via the caller's `onStatusUpdate` callback. Shared by every screen
/// that changes an engine setting (model, language, threads, custom words), so
/// all of them reload through the same path the shared engine uses.
fn spawn_reload(env: JNIEnv, activity: JObject) {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info),
    );
    let vm = match env.get_java_vm() {
        Ok(vm) => Arc::new(vm),
        Err(_) => return,
    };
    let activity_ref = match env.new_global_ref(&activity) {
        Ok(r) => r,
        Err(_) => return,
    };
    std::thread::spawn(move || {
        engine::reset();
        let _ = engine::ensure_loaded_from_thread(&vm, &activity_ref);
    });
}

/// Called after the user changes the model selection (or language hint):
/// drops the current engine and reloads with the new selection, reporting
/// progress via the activity's `onStatusUpdate` callback.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_ModelsActivity_reloadModelNative(
    env: JNIEnv,
    _class: JClass,
    activity: JObject,
) {
    spawn_reload(env, activity);
}

/// Called after the user edits the custom-word list: reloads the engine so the
/// new initial prompt takes effect on the next transcription.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_CustomWordsActivity_reloadModelNative(
    env: JNIEnv,
    _class: JClass,
    activity: JObject,
) {
    spawn_reload(env, activity);
}
