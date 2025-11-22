#[cfg(target_os = "android")]
use android_activity::AndroidApp;
#[cfg(target_os = "android")]
use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use eframe::egui;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::thread;
use transcribe_rs::engines::parakeet::{ParakeetEngine, ParakeetModelParams};
use transcribe_rs::TranscriptionEngine;

// JNI imports
#[cfg(target_os = "android")]
use jni::JNIEnv;
#[cfg(target_os = "android")]
use jni::objects::{JClass, JObject};

#[cfg(target_os = "android")]
#[no_mangle]
fn android_main(app: AndroidApp) {
    android_logger::init_once(android_logger::Config::default().with_max_level(log::LevelFilter::Info));
    
    std::panic::set_hook(Box::new(|info| {
        log::error!("PANIC: {}", info);
    }));

    log::info!("Starting Android Transcribe App Activity");

    if let Err(e) = ort::init().commit() {
         log::error!("Failed to initialize ORT: {}", e);
    }

    let mut options = eframe::NativeOptions::default();
    options.android_app = Some(app);

    let result = eframe::run_native(
        "Offline Voice Input",
        options,
        Box::new(|cc| Ok(Box::new(TranscribeApp::new(cc)))),
    );
    
    if let Err(e) = result {
        log::error!("eframe run_native failed: {}", e);
    }
}

#[derive(PartialEq, Clone, Copy)]
enum OnboardingStep {
    Permissions,
    ImeSetup,
    AssetExtraction,
    Ready,
}

struct TranscribeApp {
    step: OnboardingStep,
    status_msg: String,
    update_receiver: crossbeam_channel::Receiver<UiUpdate>,
    update_sender: crossbeam_channel::Sender<UiUpdate>,
}

enum UiUpdate {
    Status(String),
    Error(String),
    AssetsReady,
    PermissionGranted,
}

impl TranscribeApp {
    fn new(_cc: &eframe::CreationContext<'_>) -> Self {
        let (sender, receiver) = crossbeam_channel::unbounded();
        let mut app = Self {
            step: OnboardingStep::Permissions,
            status_msg: "Initializing...".to_string(),
            update_receiver: receiver,
            update_sender: sender.clone(),
        };
        
        // Initial check
        app.check_permissions();

        app
    }

    fn check_permissions(&mut self) {
        #[cfg(target_os = "android")]
        {
            let has_perm = check_permission("android.permission.RECORD_AUDIO").unwrap_or(false);
            if has_perm {
                self.step = OnboardingStep::AssetExtraction;
                self.start_asset_extraction();
            } else {
                self.step = OnboardingStep::Permissions;
            }
        }
        #[cfg(not(target_os = "android"))]
        {
            self.step = OnboardingStep::AssetExtraction;
            self.start_asset_extraction();
        }
    }
    
    fn request_permissions(&self) {
        #[cfg(target_os = "android")]
        {
            let _ = request_permission("android.permission.RECORD_AUDIO");
        }
    }

    fn start_asset_extraction(&self) {
        let sender = self.update_sender.clone();
        
        thread::spawn(move || {
            sender.send(UiUpdate::Status("Checking model assets...".to_string())).ok();
            
            #[cfg(target_os = "android")]
            let _ = {
                let ctx = ndk_context::android_context();
                let vm = unsafe { jni::JavaVM::from_raw(ctx.vm().cast()) }.unwrap();
                let mut env = vm.attach_current_thread().unwrap();
                let activity = unsafe { JObject::from_raw(ctx.context().cast()) };
                
                match extract_assets(&mut env, &activity) {
                    Ok(path) => path,
                    Err(e) => {
                        sender.send(UiUpdate::Error(format!("Asset extraction failed: {}", e))).ok();
                        return;
                    }
                }
            };

            sender.send(UiUpdate::AssetsReady).ok();
        });
    }
}

impl eframe::App for TranscribeApp {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        while let Ok(update) = self.update_receiver.try_recv() {
            match update {
                UiUpdate::Status(msg) => self.status_msg = msg,
                UiUpdate::Error(e) => {
                    self.status_msg = format!("Error: {}", e);
                },
                UiUpdate::AssetsReady => {
                    self.status_msg = "Assets Ready.".to_string();
                    if self.step == OnboardingStep::AssetExtraction {
                        self.step = OnboardingStep::Ready;
                    }
                },
                UiUpdate::PermissionGranted => {
                    if self.step == OnboardingStep::Permissions {
                        self.step = OnboardingStep::AssetExtraction;
                        self.start_asset_extraction();
                    }
                }
            }
        }

        egui::CentralPanel::default().show(ctx, |ui| {
            ui.vertical_centered(|ui| {
                ui.add_space(60.0);
                ui.heading("Offline Voice Input");
                ui.add_space(10.0);
                
                match self.step {
                    OnboardingStep::Permissions => {
                        ui.label("Welcome! To use this app, we need permission to record audio.");
                        ui.add_space(20.0);
                        if ui.button("Grant Microphone Permission").clicked() {
                            self.request_permissions();
                        }
                        ui.add_space(10.0);
                        if ui.button("I have granted it (Check again)").clicked() {
                             self.check_permissions();
                        }
                    }
                    OnboardingStep::ImeSetup => {
                        // Deprecated step, but kept for enum compatibility
                        ui.label("Permissions Granted!");
                    }
                    OnboardingStep::AssetExtraction => {
                        ui.spinner();
                        ui.add_space(10.0);
                        ui.label(&self.status_msg);
                    }
                    OnboardingStep::Ready => {
                        ui.label("Setup Complete.");
                        ui.add_space(10.0);
                        
                        if ui.button("Start Live Subtitles").clicked() {
                            #[cfg(target_os = "android")]
                            if let Err(e) = start_live_subtitles() {
                                self.status_msg = format!("Failed to start: {}", e);
                            }
                        }
                        
                        ui.add_space(20.0);
                        ui.separator();
                        ui.heading("Keyboard Setup (Optional)");
                        ui.label("To use Voice Input in other apps:");
                        ui.label("1. Enable 'Offline Voice Input' in Settings.");
                        ui.label("2. Switch keyboard when typing.");
                        
                        #[cfg(target_os = "android")]
                        if ui.button("Open Keyboard Settings").clicked() {
                            let _ = open_ime_settings();
                        }

                        ui.add_space(10.0);
                        ui.label("Status:");
                        ui.label(&self.status_msg);
                    }
                }
            });
        });
    }
}

// ===========================================================================
// JNI Utilities & Service
// ===========================================================================

#[cfg(target_os = "android")]
fn start_live_subtitles() -> anyhow::Result<()> {
    let ctx = ndk_context::android_context();
    let vm = unsafe { jni::JavaVM::from_raw(ctx.vm().cast()) }?;
    let mut env = vm.attach_current_thread()?;
    let activity = unsafe { JObject::from_raw(ctx.context().cast()) };
    
    let intent_class = env.find_class("android/content/Intent")?;
    let intent_obj = env.new_object(&intent_class, "()V", &[])?;
    let pkg_name = env.new_string("dev.notune.transcribe")?;
    let cls_name = env.new_string("dev.notune.transcribe.LiveSubtitleActivity")?;
    
    env.call_method(
        &intent_obj,
        "setClassName",
        "(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;",
        &[(&pkg_name).into(), (&cls_name).into()]
    )?;
    
    // FLAG_ACTIVITY_NEW_TASK
    env.call_method(
        &intent_obj, 
        "addFlags", 
        "(I)Landroid/content/Intent;", 
        &[268435456.into()]
    )?;

    env.call_method(
        &activity,
        "startActivity",
        "(Landroid/content/Intent;)V",
        &[(&intent_obj).into()]
    )?;
    
    Ok(())
}

#[cfg(target_os = "android")]
fn check_permission(perm_name: &str) -> anyhow::Result<bool> {
    let ctx = ndk_context::android_context();
    let vm = unsafe { jni::JavaVM::from_raw(ctx.vm().cast()) }?;
    let mut env = vm.attach_current_thread()?;
    let activity = unsafe { JObject::from_raw(ctx.context().cast()) };
    
    let perm_jstring = env.new_string(perm_name)?;
    let check_result = env.call_method(
        &activity, 
        "checkSelfPermission", 
        "(Ljava/lang/String;)I", 
        &[(&perm_jstring).into()]
    )?.i()?;
    
    Ok(check_result == 0)
}

#[cfg(target_os = "android")]
fn request_permission(perm_name: &str) -> anyhow::Result<()> {
    let ctx = ndk_context::android_context();
    let vm = unsafe { jni::JavaVM::from_raw(ctx.vm().cast()) }?;
    let mut env = vm.attach_current_thread()?;
    let activity = unsafe { JObject::from_raw(ctx.context().cast()) };
    
    let perm_jstring = env.new_string(perm_name)?;
    let array_j = env.new_object_array(1, "java/lang/String", &perm_jstring)?;
    
    env.call_method(
        &activity,
        "requestPermissions",
        "([Ljava/lang/String;I)V",
        &[
            (&array_j).into(),
            (0i32).into()
        ]
    )?;
    Ok(())
}

#[cfg(target_os = "android")]
fn open_ime_settings() -> anyhow::Result<()> {
    let ctx = ndk_context::android_context();
    let vm = unsafe { jni::JavaVM::from_raw(ctx.vm().cast()) }?;
    let mut env = vm.attach_current_thread()?;
    let activity = unsafe { JObject::from_raw(ctx.context().cast()) };
    
    let intent_class = env.find_class("android/content/Intent")?;
    let action_string = env.new_string("android.settings.INPUT_METHOD_SETTINGS")?;
    let intent_obj = env.new_object(
        &intent_class, 
        "(Ljava/lang/String;)V", 
        &[(&action_string).into()]
    )?;
    
    env.call_method(
        &activity,
        "startActivity",
        "(Landroid/content/Intent;)V",
        &[(&intent_obj).into()]
    )?;
    
    Ok(())
}

#[cfg(target_os = "android")]
fn extract_assets(
    env: &mut jni::JNIEnv, 
    context: &jni::objects::JObject, 
) -> anyhow::Result<PathBuf> {
    let files_dir_obj = env.call_method(context, "getFilesDir", "()Ljava/io/File;", &[])?.l()?;
    let path_str_obj = env.call_method(&files_dir_obj, "getAbsolutePath", "()Ljava/lang/String;", &[])?.l()?;
    let path_string: String = env.get_string(&path_str_obj.into())?.into();
    
    let base_path = PathBuf::from(path_string);
    let model_dir = base_path.join("parakeet-tdt-0.6b-v3-int8");
    
    if model_dir.exists() {
         if std::fs::read_dir(&model_dir)?.count() > 0 {
             return Ok(model_dir);
         }
    }
    
    std::fs::create_dir_all(&model_dir)?;
    
    let asset_manager_obj = env.call_method(context, "getAssets", "()Landroid/content/res/AssetManager;", &[])?.l()?;
    let asset_dir_name = "parakeet-tdt-0.6b-v3-int8";
    
    copy_assets_recursively(env, &asset_manager_obj, asset_dir_name, &base_path)?;
    
    Ok(model_dir)
}

#[cfg(target_os = "android")]
fn copy_assets_recursively(
    env: &mut jni::JNIEnv, 
    asset_manager: &jni::objects::JObject, 
    path: &str, 
    target_root: &PathBuf
) -> anyhow::Result<()> {
    use jni::objects::JObjectArray;

    let path_jstring = env.new_string(path)?;
    let list_array_obj = env.call_method(
        asset_manager, 
        "list", 
        "(Ljava/lang/String;)[Ljava/lang/String;", 
        &[(&path_jstring).into()]
    )?.l()?;
    
    let list_array: JObjectArray = list_array_obj.into();
    let len = env.get_array_length(&list_array)?;
    
    if len == 0 {
        return copy_asset_file(env, asset_manager, path, target_root);
    }

    let target_dir = target_root.join(path);
    std::fs::create_dir_all(&target_dir)?;
    
    for i in 0..len {
        let file_name_obj = env.get_object_array_element(&list_array, i)?;
        let file_name: String = env.get_string(&file_name_obj.into())?.into();
        
        let child_path = if path.is_empty() {
            file_name
        } else {
            format!("{}/{}", path, file_name)
        };
        
        copy_assets_recursively(env, asset_manager, &child_path, target_root)?;
    }
    Ok(())
}

#[cfg(target_os = "android")]
fn copy_asset_file(
    env: &mut jni::JNIEnv, 
    asset_manager: &jni::objects::JObject, 
    asset_path: &str, 
    target_root: &PathBuf
) -> anyhow::Result<()> {
    let path_jstring = env.new_string(asset_path)?;
    let result = env.call_method(
        asset_manager, 
        "open", 
        "(Ljava/lang/String;)Ljava/io/InputStream;", 
        &[(&path_jstring).into()]
    );
    
    match result {
        Ok(stream_val) => {
            let stream_obj = stream_val.l()?;
            let target_file_path = target_root.join(asset_path);
            
            let mut file = std::fs::File::create(&target_file_path)?;
            let mut buffer = [0u8; 8192];
            let buffer_j = env.new_byte_array(8192)?;
            
            loop {
                let bytes_read = env.call_method(
                    &stream_obj, 
                    "read", 
                    "([B)I", 
                    &[(&buffer_j).into()]
                )?.i()?;
                
                if bytes_read == -1 {
                    break;
                }
                
                let bytes_read_usize = bytes_read as usize;
                let buffer_slice = unsafe { 
                    std::slice::from_raw_parts_mut(buffer.as_mut_ptr() as *mut i8, bytes_read_usize) 
                };
                
                env.get_byte_array_region(&buffer_j, 0, buffer_slice)?;
                
                use std::io::Write;
                file.write_all(&buffer[0..bytes_read_usize])?;
            }
            
            env.call_method(&stream_obj, "close", "()V", &[])?;
            log::info!("Extracted: {:?}", target_file_path);
            Ok(())
        },
        Err(_) => Ok(())
    }
}

// ===========================================================================
// IME & Live Subtitle Implementation
// ===========================================================================

#[cfg(target_os = "android")]
struct SendStream(cpal::Stream);
#[cfg(target_os = "android")]
unsafe impl Send for SendStream {}
#[cfg(target_os = "android")]
unsafe impl Sync for SendStream {}

#[cfg(target_os = "android")]
struct ImeState {
    stream: Option<SendStream>,
    audio_buffer: Arc<Mutex<Vec<f32>>>,
    jvm: Arc<jni::JavaVM>,
    service_ref: jni::objects::GlobalRef,
}

#[cfg(target_os = "android")]
static IME_STATE: Mutex<Option<ImeState>> = Mutex::new(None);
#[cfg(target_os = "android")]
static GLOBAL_ENGINE: Mutex<Option<Arc<Mutex<ParakeetEngine>>>> = Mutex::new(None);

#[cfg(target_os = "android")]
fn ensure_engine_loaded(
    vm: &Arc<jni::JavaVM>,
    service_ref: &jni::objects::GlobalRef,
    callback_method: &str
) {
    // Check if already loaded
    if GLOBAL_ENGINE.lock().unwrap().is_some() {
        if let Ok(mut env) = vm.attach_current_thread() {
            let msg = env.new_string("Ready").unwrap();
            let _ = env.call_method(service_ref.as_obj(), callback_method, "(Ljava/lang/String;)V", &[(&msg).into()]);
        }
        return;
    }

    // Not loaded, spawn loader
    let vm_clone = vm.clone();
    let service_ref_clone = service_ref.clone();
    let cb_name = callback_method.to_string();
    
    std::thread::spawn(move || {
        let mut env = vm_clone.attach_current_thread().unwrap();
        let service_obj = service_ref_clone.as_obj();
        
        // Notify loading
        let msg = env.new_string("Initializing model...").unwrap();
        let _ = env.call_method(service_obj, &cb_name, "(Ljava/lang/String;)V", &[(&msg).into()]);

        match extract_assets(&mut env, service_obj) {
            Ok(path) => {
                 let msg = env.new_string("Loading model...").unwrap();
                 let _ = env.call_method(service_obj, &cb_name, "(Ljava/lang/String;)V", &[(&msg).into()]);
                 
                 let mut engine = ParakeetEngine::new();
                 match engine.load_model_with_params(&path, ParakeetModelParams::int8()) {
                     Ok(_) => {
                         *GLOBAL_ENGINE.lock().unwrap() = Some(Arc::new(Mutex::new(engine)));
                         let msg = env.new_string("Ready").unwrap();
                         let _ = env.call_method(service_obj, &cb_name, "(Ljava/lang/String;)V", &[(&msg).into()]);
                     },
                     Err(e) => {
                         let msg = env.new_string(format!("Error: {}", e)).unwrap();
                         let _ = env.call_method(service_obj, &cb_name, "(Ljava/lang/String;)V", &[(&msg).into()]);
                     }
                 }
            },
            Err(e) => {
                let msg = env.new_string(format!("Error: {}", e)).unwrap();
                let _ = env.call_method(service_obj, &cb_name, "(Ljava/lang/String;)V", &[(&msg).into()]);
            }
        }
    });
}

// --- IME JNI ---

#[cfg(target_os = "android")]
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_initNative(
    mut env: JNIEnv,
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
    
    drop(state_guard);
    
    ensure_engine_loaded(&vm_arc, &service_ref, "onStatusUpdate");
}

#[cfg(target_os = "android")]
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_cleanupNative(
    _env: JNIEnv,
    _class: JClass,
) {
    *IME_STATE.lock().unwrap() = None;
}

#[cfg(target_os = "android")]
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
             
             let msg = env.new_string("Listening...").unwrap();
             let _ = env.call_method(state.service_ref.as_obj(), "onStatusUpdate", "(Ljava/lang/String;)V", &[(&msg).into()]);
         }
    }
}

#[cfg(target_os = "android")]
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
    
    let engine_arc = {
        let guard = GLOBAL_ENGINE.lock().unwrap();
        if guard.is_none() { return; }
        guard.as_ref().unwrap().clone()
    };

    let msg = env.new_string("Transcribing...").unwrap();
    let _ = env.call_method(service_ref.as_obj(), "onStatusUpdate", "(Ljava/lang/String;)V", &[(&msg).into()]);
    
    std::thread::spawn(move || {
        let mut env = jvm.attach_current_thread().unwrap();
        let service_obj = service_ref.as_obj();
        
        let res = {
             let mut eng = engine_arc.lock().unwrap();
             eng.transcribe_samples(buffer, None)
        };
        
        match res {
            Ok(r) => {
                let msg = env.new_string("Ready").unwrap();
                let _ = env.call_method(service_obj, "onStatusUpdate", "(Ljava/lang/String;)V", &[(&msg).into()]);
                let txt = env.new_string(r.text).unwrap();
                let _ = env.call_method(service_obj, "onTextTranscribed", "(Ljava/lang/String;)V", &[(&txt).into()]);
            },
            Err(e) => {
                let msg = env.new_string(format!("Error: {}", e)).unwrap();
                let _ = env.call_method(service_obj, "onStatusUpdate", "(Ljava/lang/String;)V", &[(&msg).into()]);
            }
        }
    });
}

// --- Live Subtitles JNI ---

use transcribe_rs::engines::parakeet::{ParakeetInferenceParams, TimestampGranularity};

#[cfg(target_os = "android")]
struct LiveSubtitleState {
    buffer: Arc<Mutex<Vec<f32>>>,
    worker_tx: crossbeam_channel::Sender<(Vec<f32>, f32)>,
}

#[cfg(target_os = "android")]
static LIVE_STATE: Mutex<Option<LiveSubtitleState>> = Mutex::new(None);

#[cfg(target_os = "android")]
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_LiveSubtitleService_initNative(
    mut env: JNIEnv,
    _class: JClass,
    service: JObject,
) {
    android_logger::init_once(android_logger::Config::default().with_max_level(log::LevelFilter::Info));
    let vm = env.get_java_vm().expect("Failed to get JavaVM");
    let vm_arc = Arc::new(vm);
    let service_ref = env.new_global_ref(&service).expect("Failed to ref service");

    let (tx, rx) = crossbeam_channel::unbounded();

    let mut state_guard = LIVE_STATE.lock().unwrap();
    *state_guard = Some(LiveSubtitleState {
        buffer: Arc::new(Mutex::new(Vec::new())),
        worker_tx: tx,
    });
    drop(state_guard);

    // Ensure engine loaded first
    ensure_engine_loaded(&vm_arc, &service_ref, "onSubtitleText");

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
        
        while let Ok((samples, overlap_sec)) = rx.recv() {
            let engine_arc_opt = GLOBAL_ENGINE.lock().unwrap().clone();
            if let Some(engine_arc) = engine_arc_opt {
                let params = ParakeetInferenceParams {
                    timestamp_granularity: TimestampGranularity::Word,
                };

                let res = {
                    let mut eng = engine_arc.lock().unwrap();
                    eng.transcribe_samples(samples, Some(params))
                };
                
                if let Ok(r) = res {
                    let mut new_text = String::new();
                    
                    if let Some(segments) = r.segments {
                        for seg in segments {
                            // Filter words that started in the overlap region
                            // Use a small margin (0.1s) to avoid dropping words right on the boundary
                            if seg.start >= (overlap_sec - 0.1).max(0.0) {
                                if !new_text.is_empty() {
                                    new_text.push(' ');
                                }
                                new_text.push_str(&seg.text);
                            }
                        }
                    } else {
                        // Fallback if no segments (shouldn't happen with granularity set)
                        new_text = r.text;
                    }

                    let text_trim = new_text.trim();
                    if !text_trim.is_empty() {
                        if let Ok(txt) = env.new_string(text_trim) {
                            let _ = env.call_method(service_obj, "onSubtitleText", "(Ljava/lang/String;)V", &[(&txt).into()]);
                        }
                    }
                }
            }
        }
    });
}

#[cfg(target_os = "android")]
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_LiveSubtitleService_cleanupNative(
    _env: JNIEnv,
    _class: JClass,
) {
    *LIVE_STATE.lock().unwrap() = None;
}

#[cfg(target_os = "android")]
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_LiveSubtitleService_pushAudio(
    env: JNIEnv,
    _class: JClass,
    data: jni::objects::JFloatArray,
    length: jni::sys::jint,
) {
    let (buffer_arc, tx) = {
        let guard = LIVE_STATE.lock().unwrap();
        if let Some(state) = guard.as_ref() {
            (state.buffer.clone(), state.worker_tx.clone())
        } else {
            return;
        }
    };

    let mut buffer = buffer_arc.lock().unwrap();
    let mut input = vec![0.0f32; length as usize];
    env.get_float_array_region(&data, 0, &mut input).unwrap();
    buffer.extend_from_slice(&input);

    // Total buffer target: 4.5s (72000 samples)
    // Overlap target: ~3s (48000 samples)
    // Trigger threshold: 1.5s (24000 samples) of NEW data.
    // Simplified Logic:
    // If buffer len > 72000:
    //   Send ALL (72000) with overlap = 48000 (3s).
    //   Slide to keep last 48000.
    
    if buffer.len() >= 72000 {
        // Simple RMS check on the *new* part (last 24000)
        let new_part_start = buffer.len() - 24000;
        let sum_sq: f32 = buffer[new_part_start..].iter().map(|&x| x * x).sum();
        let rms = (sum_sq / 24000.0).sqrt();
        
        if rms > 0.002 {
            let samples_to_transcribe = buffer.clone();
            // Overlap is the part BEFORE the new data
            let overlap_sec = (buffer.len() - 24000) as f32 / 16000.0;
            let _ = tx.send((samples_to_transcribe, overlap_sec));
        }
        
        // Slide: Keep last 48000 (3s)
        let new_buf = buffer[new_part_start..].to_vec();
        // Wait, if we keep just the new part, we lose context for NEXT turn.
        // We want to keep [Context] + [New].
        // We want next turn to have [Context=48000] + [New=24000].
        // So we must keep 48000 samples.
        let keep_len = 48000;
        let start_idx = buffer.len() - keep_len;
        let new_buf_vec = buffer[start_idx..].to_vec();
        *buffer = new_buf_vec;
    }
}
