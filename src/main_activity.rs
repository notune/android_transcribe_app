use jni::JNIEnv;
use jni::objects::{JClass, JObject};
use std::sync::Arc;
use crate::{assets, engine};
use transcribe_rs::TranscriptionEngine;

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_MainActivity_initNative(
    env: JNIEnv,
    _class: JClass,
    activity: JObject,
) {
    android_logger::init_once(android_logger::Config::default().with_max_level(log::LevelFilter::Info));
    
    // Initialize ORT if not already
    let _ = ort::init().commit();
    
    let vm = env.get_java_vm().expect("Failed to get JavaVM");
    let vm_arc = Arc::new(vm);
    let activity_ref = env.new_global_ref(&activity).expect("Failed to ref activity");
    
    std::thread::spawn(move || {
        if let Ok(mut env) = vm_arc.attach_current_thread() {
            let act = activity_ref.as_obj();
            
            notify_status(&mut env, act, "Checking assets...");
            
            match assets::extract_assets(&mut env, act) {
                Ok(path) => {
                    notify_status(&mut env, act, "Loading model...");
                    // Load engine if not loaded
                    if !engine::is_engine_loaded() {
                        let mut eng = transcribe_rs::engines::parakeet::ParakeetEngine::new();
                        match eng.load_model_with_params(&path, transcribe_rs::engines::parakeet::ParakeetModelParams::int8()) {
                            Ok(_) => {
                                engine::set_engine(eng);
                                notify_status(&mut env, act, "Ready");
                            },
                            Err(e) => notify_status(&mut env, act, &format!("Model Error: {}", e)),
                        }
                    } else {
                         notify_status(&mut env, act, "Ready");
                    }
                },
                Err(e) => notify_status(&mut env, act, &format!("Asset Error: {}", e)),
            }
        }
    });
}

fn notify_status(env: &mut JNIEnv, obj: &JObject, msg: &str) {
    if let Ok(jmsg) = env.new_string(msg) {
        let _ = env.call_method(obj, "onStatusUpdate", "(Ljava/lang/String;)V", &[(&jmsg).into()]);
    }
}
