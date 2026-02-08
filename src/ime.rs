use std::sync::{Arc, Mutex};
use std::sync::atomic::{AtomicBool, Ordering};
use jni::JNIEnv;
use jni::objects::{JClass, JObject, GlobalRef};
use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use once_cell::sync::Lazy;
use transcribe_rs::TranscriptionEngine;

use crate::engine;
use crate::assets;

/// Tracks whether the model is currently being loaded (true = loading in progress)
static MODEL_LOADING: AtomicBool = AtomicBool::new(false);

struct SendStream(#[allow(dead_code)] cpal::Stream);
unsafe impl Send for SendStream {}
unsafe impl Sync for SendStream {}

struct ImeState {
    stream: Option<SendStream>,
    audio_buffer: Arc<Mutex<Vec<f32>>>,
    jvm: Arc<jni::JavaVM>,
    service_ref: GlobalRef,
}

static IME_STATE: Lazy<Mutex<Option<ImeState>>> = Lazy::new(|| Mutex::new(None));

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_initNative(
    env: JNIEnv,
    _class: JClass,
    service: JObject,
) {
    android_logger::init_once(android_logger::Config::default().with_max_level(log::LevelFilter::Info));
    let vm = env.get_java_vm().expect("Failed to get JavaVM");
    let vm_arc = Arc::new(vm);
    let service_ref = env.new_global_ref(&service).expect("Failed to ref service");
    
    let mut state_guard = IME_STATE.lock().unwrap();
    *state_guard = Some(ImeState {
        stream: None,
        audio_buffer: Arc::new(Mutex::new(Vec::new())),
        jvm: vm_arc.clone(),
        service_ref: service_ref.clone(),
    });
    
    // Trigger lazy loading of engine if needed, but for IME we usually wait for main app.
    // However, if IME starts first (possible), we must ensure model is there.
    let vm_clone = vm_arc.clone();
    let service_ref_clone = service_ref.clone();
    
    std::thread::spawn(move || {
        if !engine::is_engine_loaded() {
            MODEL_LOADING.store(true, Ordering::Release);
            if let Ok(mut env) = vm_clone.attach_current_thread() {
                 let srv = service_ref_clone.as_obj();
                 notify_status(&mut env, srv, "Loading model...");

                 // Attempt extraction/loading
                 match assets::extract_assets(&mut env, srv) {
                     Ok(path) => {
                         let mut eng = transcribe_rs::engines::parakeet::ParakeetEngine::new();
                         match eng.load_model_with_params(&path, transcribe_rs::engines::parakeet::ParakeetModelParams::int8()) {
                             Ok(_) => {
                                 engine::set_engine(eng);
                                 MODEL_LOADING.store(false, Ordering::Release);
                                 notify_status(&mut env, srv, "Ready");
                             },
                             Err(e) => {
                                 MODEL_LOADING.store(false, Ordering::Release);
                                 notify_status(&mut env, srv, &format!("Error: {}", e));
                             },
                         }
                     },
                     Err(e) => {
                         MODEL_LOADING.store(false, Ordering::Release);
                         notify_status(&mut env, srv, &format!("Error: {}", e));
                     },
                 }
            } else {
                MODEL_LOADING.store(false, Ordering::Release);
            }
        } else {
             if let Ok(mut env) = vm_clone.attach_current_thread() {
                 notify_status(&mut env, service_ref_clone.as_obj(), "Ready");
             }
        }
    });
}

fn notify_status(env: &mut JNIEnv, obj: &JObject, msg: &str) {
    if let Ok(jmsg) = env.new_string(msg) {
        let _ = env.call_method(obj, "onStatusUpdate", "(Ljava/lang/String;)V", &[(&jmsg).into()]);
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_cleanupNative(
    _env: JNIEnv,
    _class: JClass,
) {
    *IME_STATE.lock().unwrap() = None;
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_startRecording(
    mut env: JNIEnv,
    _class: JClass,
) {
    let mut state_guard = IME_STATE.lock().unwrap();
    if let Some(state) = state_guard.as_mut() {
         let host = cpal::default_host();
         let device = match host.default_input_device() {
             Some(d) => d,
             None => return,
         };
         
         let config = cpal::StreamConfig {
            channels: 1,
            sample_rate: cpal::SampleRate(16000),
            buffer_size: cpal::BufferSize::Default,
         };
         
         state.audio_buffer.lock().unwrap().clear();
         let buffer_clone = state.audio_buffer.clone();
         
         let stream = device.build_input_stream(
             &config,
             move |data: &[f32], _: &_| {
                 buffer_clone.lock().unwrap().extend_from_slice(data);
             },
             |e| log::error!("Stream err: {}", e),
             None,
         );
         
         if let Ok(s) = stream {
             s.play().ok();
             state.stream = Some(SendStream(s));
             notify_status(&mut env, state.service_ref.as_obj(), "Listening...");
         }
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_stopRecording(
    mut env: JNIEnv,
    _class: JClass,
) {
    let (buffer, jvm, service_ref) = {
        let mut state_guard = IME_STATE.lock().unwrap();
        if let Some(state) = state_guard.as_mut() {
            state.stream = None;
            (state.audio_buffer.lock().unwrap().clone(), state.jvm.clone(), state.service_ref.clone())
        } else {
            return;
        }
    };
    
    notify_status(&mut env, service_ref.as_obj(), "Transcribing...");

    std::thread::spawn(move || {
        let mut env = jvm.attach_current_thread().unwrap();
        let service_obj = service_ref.as_obj();

        // If engine not ready, wait for model to finish loading
        if engine::get_engine().is_none() && MODEL_LOADING.load(Ordering::Acquire) {
            notify_status(&mut env, service_obj, "Waiting for model...");
            let start = std::time::Instant::now();
            while engine::get_engine().is_none() && MODEL_LOADING.load(Ordering::Acquire) {
                if start.elapsed() > std::time::Duration::from_secs(120) {
                    notify_status(&mut env, service_obj, "Error: timeout waiting for model");
                    return;
                }
                std::thread::sleep(std::time::Duration::from_millis(200));
            }
        }

        let engine_opt = engine::get_engine();
        if let Some(eng_arc) = engine_opt {
             let res = {
                 let mut eng = eng_arc.lock().unwrap();
                 eng.transcribe_samples(buffer, None)
             };
             
             match res {
                Ok(r) => {
                    notify_status(&mut env, service_obj, "Ready");
                    if let Ok(txt) = env.new_string(r.text) {
                        let _ = env.call_method(service_obj, "onTextTranscribed", "(Ljava/lang/String;)V", &[(&txt).into()]);
                    }
                },
                Err(e) => notify_status(&mut env, service_obj, &format!("Error: {}", e)),
            }
        } else {
            notify_status(&mut env, service_obj, "Error: model failed to load");
        }
    });
}
