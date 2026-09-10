use crate::engine;
use jni::objects::{JClass, JObject};
use jni::JNIEnv;
use std::sync::Arc;

/// Reports a finished benchmark run back to `MainActivity.onBenchmarkResult`.
/// `error` is empty on success.
fn deliver_benchmark_result(
    env: &mut JNIEnv,
    activity: &JObject,
    audio_secs: f32,
    compute_secs: f32,
    error: &str,
) {
    if let Ok(jerr) = env.new_string(error) {
        let _ = env.call_method(
            activity,
            "onBenchmarkResult",
            "(FFLjava/lang/String;)V",
            &[audio_secs.into(), compute_secs.into(), (&jerr).into()],
        );
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_MainActivity_initNative(
    env: JNIEnv,
    _class: JClass,
    activity: JObject,
) {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info),
    );

    let vm = env.get_java_vm().expect("Failed to get JavaVM");
    let vm_arc = Arc::new(vm);
    let activity_ref = env
        .new_global_ref(&activity)
        .expect("Failed to ref activity");

    std::thread::spawn(move || {
        let _ = engine::ensure_loaded_from_thread(&vm_arc, &activity_ref);
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_MainActivity_retrySetupNative(
    env: JNIEnv,
    _class: JClass,
    activity: JObject,
) {
    let vm = match env.get_java_vm() {
        Ok(vm) => Arc::new(vm),
        Err(_) => return,
    };
    let activity_ref = match env.new_global_ref(&activity) {
        Ok(reference) => reference,
        Err(_) => return,
    };
    std::thread::spawn(move || {
        engine::reset();
        let _ = engine::ensure_loaded_from_thread(&vm, &activity_ref);
    });
}

/// Benchmark: transcribes the given samples once on a worker thread and calls
/// back with audio seconds vs compute seconds. Engine loading (if it is still
/// in progress) is waited for but not counted into the measured time.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_MainActivity_benchmarkNative(
    env: JNIEnv,
    _class: JClass,
    activity: JObject,
    samples: jni::objects::JFloatArray,
    length: jni::sys::jint,
) {
    let len = length as usize;
    let mut buffer = vec![0.0f32; len];
    if len == 0
        || env
            .get_float_array_region(&samples, 0, &mut buffer)
            .is_err()
    {
        return;
    }

    let vm = match env.get_java_vm() {
        Ok(vm) => Arc::new(vm),
        Err(_) => return,
    };
    let activity_ref = match env.new_global_ref(&activity) {
        Ok(r) => r,
        Err(_) => return,
    };

    std::thread::spawn(move || {
        let mut env = match vm.attach_current_thread() {
            Ok(e) => e,
            Err(_) => return,
        };
        let audio_secs = buffer.len() as f32 / 16_000.0;

        if let Err(e) = engine::ensure_loaded_from_thread(&vm, &activity_ref) {
            deliver_benchmark_result(&mut env, &activity_ref.as_obj(), audio_secs, 0.0, &e);
            return;
        }
        let engine_arc = match engine::get_engine() {
            Some(e) => e,
            None => {
                deliver_benchmark_result(
                    &mut env,
                    &activity_ref.as_obj(),
                    audio_secs,
                    0.0,
                    "model not loaded",
                );
                return;
            }
        };

        let started = std::time::Instant::now();
        let result = engine::transcribe_shared(&engine_arc, buffer);
        let compute_secs = started.elapsed().as_secs_f32();

        match result {
            Ok(text) => {
                log::info!(
                    "benchmark: {:.1}s audio in {:.2}s -> {:?}",
                    audio_secs,
                    compute_secs,
                    text
                );
                deliver_benchmark_result(
                    &mut env,
                    &activity_ref.as_obj(),
                    audio_secs,
                    compute_secs,
                    "",
                );
            }
            Err(e) => {
                deliver_benchmark_result(&mut env, &activity_ref.as_obj(), audio_secs, 0.0, &e);
            }
        }
    });
}
