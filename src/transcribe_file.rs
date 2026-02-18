use std::sync::{Arc, Mutex};

use jni::objects::{JClass, JFloatArray, JObject};
use jni::sys::jint;
use jni::JNIEnv;
use once_cell::sync::Lazy;

use transcribe_rs::TranscriptionEngine;

use crate::engine;

struct TranscribeFileState {
    jvm: Arc<jni::JavaVM>,
    target_ref: jni::objects::GlobalRef,
}

static STATE: Lazy<Mutex<Option<TranscribeFileState>>> = Lazy::new(|| Mutex::new(None));

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

fn notify_text(env: &mut JNIEnv, obj: &JObject, text: &str) {
    if let Ok(jtxt) = env.new_string(text) {
        let _ = env.call_method(
            obj,
            "onTextTranscribed",
            "(Ljava/lang/String;)V",
            &[(&jtxt).into()],
        );
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_TranscribeFileActivity_initNative(
    env: JNIEnv,
    _class: JClass,
    activity: JObject,
) {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info),
    );

    let vm = env.get_java_vm().expect("Failed to get JavaVM");
    let vm_arc = Arc::new(vm);
    let target_ref = env
        .new_global_ref(&activity)
        .expect("Failed to ref activity");

    let state = TranscribeFileState {
        jvm: vm_arc.clone(),
        target_ref: target_ref.clone(),
    };
    *STATE.lock().unwrap() = Some(state);

    // Load engine in background
    let vm_clone = vm_arc.clone();
    let target_ref_clone = target_ref.clone();

    std::thread::spawn(move || {
        let _ = engine::ensure_loaded_from_thread(&vm_clone, &target_ref_clone);
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_TranscribeFileActivity_cleanupNative(
    _env: JNIEnv,
    _class: JClass,
) {
    *STATE.lock().unwrap() = None;
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_TranscribeFileActivity_transcribeAudio(
    env: JNIEnv,
    _class: JClass,
    samples_array: JFloatArray,
    length: jint,
) {
    let guard = STATE.lock().unwrap();
    let state = match guard.as_ref() {
        Some(s) => s,
        None => return,
    };

    let len = length as usize;
    if len == 0 {
        log::warn!("transcribeAudio called with empty buffer");
        let jvm = state.jvm.clone();
        let target_ref = state.target_ref.clone();
        drop(guard);
        if let Ok(mut env) = jvm.attach_current_thread() {
            notify_status(
                &mut env,
                target_ref.as_obj(),
                "Error: no audio data to transcribe",
            );
        }
        return;
    }

    let mut buffer = vec![0.0f32; len];
    if env
        .get_float_array_region(&samples_array, 0, &mut buffer)
        .is_err()
    {
        log::error!("Failed to read float array from Java");
        return;
    }

    let jvm = state.jvm.clone();
    let target_ref = state.target_ref.clone();

    // Drop the lock before spawning the thread
    drop(guard);

    std::thread::spawn(move || {
        let mut env = match jvm.attach_current_thread() {
            Ok(e) => e,
            Err(_) => return,
        };
        let obj = target_ref.as_obj();

        // Ensure engine is loaded (waits if another thread is loading)
        if engine::get_engine().is_none() {
            if let Err(_) = engine::ensure_loaded(&mut env, obj) {
                return;
            }
        }

        if let Some(eng_arc) = engine::get_engine() {
            notify_status(&mut env, obj, "Transcribing...");

            let res = {
                let mut eng = eng_arc.lock().unwrap();
                eng.transcribe_samples(buffer, None)
            };

            match res {
                Ok(r) => {
                    notify_text(&mut env, obj, &r.text);
                }
                Err(e) => {
                    notify_status(&mut env, obj, &format!("Error: {}", e));
                }
            }
        } else {
            notify_status(&mut env, obj, "Error: model not loaded");
        }
    });
}
