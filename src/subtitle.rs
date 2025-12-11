use std::sync::{Arc, Mutex};
use jni::JNIEnv;
use jni::objects::{JClass, JObject};
use jni::sys::jfloat;
use once_cell::sync::Lazy;
use crossbeam_channel;
use transcribe_rs::engines::parakeet::{ParakeetInferenceParams, TimestampGranularity};
use transcribe_rs::TranscriptionEngine;

use crate::engine;

struct LiveSubtitleState {
    buffer: Arc<Mutex<Vec<f32>>>,
    worker_tx: crossbeam_channel::Sender<(Vec<f32>, f64)>,
    total_samples: u64,
    last_process_sample: u64,
    update_interval: usize,
}

static LIVE_STATE: Lazy<Mutex<Option<LiveSubtitleState>>> = Lazy::new(|| Mutex::new(None));

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_LiveSubtitleService_initNative(
    env: JNIEnv,
    _class: JClass,
    service: JObject,
) {
    android_logger::init_once(android_logger::Config::default().with_max_level(log::LevelFilter::Info));
    let vm = env.get_java_vm().expect("Failed to get JavaVM");
    let vm_arc = Arc::new(vm);
    let service_ref = env.new_global_ref(&service).expect("Failed to ref service");

    let (tx, rx) = crossbeam_channel::unbounded();

    let mut state_guard = LIVE_STATE.lock().unwrap();
    // Default 2s
    *state_guard = Some(LiveSubtitleState {
        buffer: Arc::new(Mutex::new(Vec::new())),
        worker_tx: tx,
        total_samples: 0,
        last_process_sample: 0,
        update_interval: 32000,
    });
    drop(state_guard);

    // Spawn Worker Thread
    let vm_worker = vm_arc.clone();
    let service_ref_worker = service_ref.clone();
    
    std::thread::spawn(move || {
        let mut env = match vm_worker.attach_current_thread() {
            Ok(e) => e,
            Err(e) => {
                log::error!("Worker failed to attach: {}", e);
                return;
            }
        };
        let service_obj = service_ref_worker.as_obj();
        let mut last_committed_end = 0.0f64;
        
        while let Ok((samples, start_time)) = rx.recv() {
            if let Some(engine_arc) = engine::get_engine() {
                let params = ParakeetInferenceParams {
                    timestamp_granularity: TimestampGranularity::Word,
                };

                let res = {
                    let mut eng = engine_arc.lock().unwrap();
                    eng.transcribe_samples(samples, Some(params))
                };
                
                if let Ok(r) = res {
                    let mut new_text = String::new();
                    let mut max_end_in_chunk = last_committed_end;
                    
                    if let Some(segments) = r.segments {
                        for seg in segments {
                            let abs_start = start_time + seg.start as f64;
                            let abs_end = start_time + seg.end as f64;
                            
                            if abs_start >= last_committed_end - 0.05 {
                                if !new_text.is_empty() {
                                    new_text.push(' ');
                                }
                                new_text.push_str(&seg.text);
                                
                                if abs_end > max_end_in_chunk {
                                    max_end_in_chunk = abs_end;
                                }
                            }
                        }
                    } else if r.text.len() > 0 {
                         new_text = r.text;
                         max_end_in_chunk = start_time + 2.0; 
                    }

                    let text_trim = new_text.trim();
                    if !text_trim.is_empty() {
                        last_committed_end = max_end_in_chunk;
                        if let Ok(txt) = env.new_string(text_trim) {
                            let _ = env.call_method(service_obj, "onSubtitleText", "(Ljava/lang/String;)V", &[(&txt).into()]);
                        }
                    }
                }
            }
        }
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_LiveSubtitleService_cleanupNative(
    _env: JNIEnv,
    _class: JClass,
) {
    *LIVE_STATE.lock().unwrap() = None;
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_LiveSubtitleService_setUpdateInterval(
    _env: JNIEnv,
    _class: JClass,
    interval_seconds: jfloat,
) {
    let mut guard = LIVE_STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        state.update_interval = (interval_seconds * 16000.0) as usize;
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_LiveSubtitleService_pushAudio(
    env: JNIEnv,
    _class: JClass,
    data: jni::objects::JFloatArray,
    length: jni::sys::jint,
) {
    let mut guard = LIVE_STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        let mut buffer = state.buffer.lock().unwrap();
        let len_usize = length as usize;
        let mut input = vec![0.0f32; len_usize];
        env.get_float_array_region(&data, 0, &mut input).unwrap();
        buffer.extend_from_slice(&input);
        state.total_samples += len_usize as u64;
        
        if state.total_samples >= state.last_process_sample + state.update_interval as u64 {
            let buffer_len = buffer.len();
            let start_time = (state.total_samples as f64 - buffer_len as f64) / 16000.0;
            let sum_sq: f32 = buffer.iter().map(|&x| x * x).sum();
            let rms = (sum_sq / buffer_len as f32).sqrt();
            
            if rms > 0.002 {
                let _ = state.worker_tx.send((buffer.clone(), start_time));
            }
            state.last_process_sample = state.total_samples;
            
            if buffer_len > 48000 {
                let keep_idx = buffer_len - 48000;
                let new_buf = buffer[keep_idx..].to_vec();
                *buffer = new_buf;
            }
        }
    }
}
