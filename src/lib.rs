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
                self.step = OnboardingStep::ImeSetup;
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
                        self.step = OnboardingStep::ImeSetup;
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
                        ui.label("Permissions Granted!");
                        ui.add_space(10.0);
                        ui.label("To use the Voice Keyboard in other apps:");
                        ui.add_space(5.0);
                        ui.label("1. Enable 'Offline Voice Input' in System Settings.");
                        ui.label("2. Switch your keyboard when typing.");
                        
                        ui.add_space(20.0);
                        #[cfg(target_os = "android")]
                        if ui.button("Open Keyboard Settings").clicked() {
                            let _ = open_ime_settings();
                        }
                        
                        ui.add_space(20.0);
                        ui.label("Once enabled, you can close this app and use the keyboard.");
                        
                        if ui.button("I have enabled it").clicked() {
                             self.step = OnboardingStep::AssetExtraction;
                             self.start_asset_extraction();
                        }
                    }
                    OnboardingStep::AssetExtraction => {
                        ui.spinner();
                        ui.add_space(10.0);
                        ui.label(&self.status_msg);
                    }
                    OnboardingStep::Ready => {
                        ui.label("Setup Complete.");
                        ui.add_space(10.0);
                        ui.label("The Offline Voice Input keyboard is ready to use.");
                        ui.label("You can close this app now.");
                        
                        ui.add_space(20.0);
                        ui.separator();
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
// IME Implementation
// ===========================================================================

#[cfg(target_os = "android")]
struct SendStream(cpal::Stream);
#[cfg(target_os = "android")]
unsafe impl Send for SendStream {}
#[cfg(target_os = "android")]
unsafe impl Sync for SendStream {}

#[cfg(target_os = "android")]
struct ImeState {
    engine: Option<Arc<Mutex<ParakeetEngine>>>,
    stream: Option<SendStream>,
    audio_buffer: Arc<Mutex<Vec<f32>>>,
    jvm: Arc<jni::JavaVM>,
    service_ref: jni::objects::GlobalRef,
}

#[cfg(target_os = "android")]
static IME_STATE: std::sync::Mutex<Option<ImeState>> = std::sync::Mutex::new(None);

#[cfg(target_os = "android")]
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_initNative(
    mut env: JNIEnv,
    _class: JClass,
    service: JObject,
) {
    android_logger::init_once(android_logger::Config::default().with_max_level(log::LevelFilter::Info));
    log::info!("IME: initNative called (PID: {})", std::process::id());

    let vm = env.get_java_vm().expect("Failed to get JavaVM");
    let service_ref = env.new_global_ref(&service).expect("Failed to ref service");
    
    let already_loaded = {
        let mut state_guard = IME_STATE.lock().unwrap();
        if let Some(state) = state_guard.as_mut() {
            // Update the service reference to the new instance
            state.service_ref = service_ref.clone();
            state.jvm = Arc::new(vm); // Update JVM
            
            // If engine is loaded, we are ready
            state.engine.is_some()
        } else {
            // First time init
            *state_guard = Some(ImeState {
                engine: None,
                stream: None,
                audio_buffer: Arc::new(Mutex::new(Vec::new())),
                jvm: Arc::new(vm),
                service_ref: service_ref.clone(),
            });
            false
        }
    };
    
    if already_loaded {
        send_ime_status(&mut env, &service, "Ready");
        return;
    }
    
    // Start init thread
    std::thread::spawn(move || {
        // Attach
        let vm = {
             let state = IME_STATE.lock().unwrap();
             if let Some(s) = state.as_ref() { s.jvm.clone() } else { return; }
        };
        let mut env = vm.attach_current_thread().unwrap();
        let service_obj = service_ref.as_obj();
        
        send_ime_status(&mut env, &service_obj, "Initializing model...");
        
        match extract_assets(&mut env, service_obj) {
            Ok(path) => {
                 send_ime_status(&mut env, &service_obj, "Loading model...");
                 let mut engine = ParakeetEngine::new();
                 match engine.load_model_with_params(&path, ParakeetModelParams::int8()) {
                     Ok(_) => {
                         {
                             let mut state = IME_STATE.lock().unwrap();
                             if let Some(s) = state.as_mut() {
                                 s.engine = Some(Arc::new(Mutex::new(engine)));
                             }
                         }
                         send_ime_status(&mut env, &service_obj, "Ready"); // Just "Ready" to match Java check
                     },
                     Err(e) => {
                         send_ime_status(&mut env, &service_obj, &format!("Error loading model: {}", e));
                     }
                 }
            },
            Err(e) => {
                send_ime_status(&mut env, &service_obj, &format!("Error extracting assets: {}", e));
            }
        }
    });
}

#[cfg(target_os = "android")]
fn send_ime_status(env: &mut JNIEnv, service: &JObject, msg: &str) {
    let msg_j = env.new_string(msg).unwrap();
    let _ = env.call_method(service, "onStatusUpdate", "(Ljava/lang/String;)V", &[(&msg_j).into()]);
}

#[cfg(target_os = "android")]
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_cleanupNative(
    _env: JNIEnv,
    _class: JClass,
) {
    log::info!("IME: cleanupNative called");
    let mut state = IME_STATE.lock().unwrap();
    *state = None;
}

#[cfg(target_os = "android")]
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_startRecording(
    mut env: JNIEnv,
    _class: JClass,
) {
    log::info!("IME Start Recording");
    let mut state_guard = IME_STATE.lock().unwrap();
    if let Some(state) = state_guard.as_mut() {
         let host = cpal::default_host();
         let device = match host.default_input_device() {
             Some(d) => d,
             None => {
                 send_ime_status(&mut env, state.service_ref.as_obj(), "No mic found");
                 return;
             }
         };
         
         let config = cpal::StreamConfig {
            channels: 1,
            sample_rate: cpal::SampleRate(16000),
            buffer_size: cpal::BufferSize::Default,
         };
         
         state.audio_buffer.lock().unwrap().clear();
         let buffer_clone = state.audio_buffer.clone();
         
         let err_fn = |err| log::error!("Stream error: {}", err);
         
         let stream = device.build_input_stream(
             &config,
             move |data: &[f32], _: &_| {
                 buffer_clone.lock().unwrap().extend_from_slice(data);
             },
             err_fn,
             None,
         );
         
         match stream {
             Ok(s) => {
                 if let Err(e) = s.play() {
                     send_ime_status(&mut env, state.service_ref.as_obj(), &format!("Stream error: {}", e));
                 } else {
                     state.stream = Some(SendStream(s));
                     send_ime_status(&mut env, state.service_ref.as_obj(), "Listening...");
                 }
             },
             Err(e) => {
                 send_ime_status(&mut env, state.service_ref.as_obj(), &format!("Mic init error: {}", e));
             }
         }
    }
}

#[cfg(target_os = "android")]
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_stopRecording(
    mut env: JNIEnv,
    _class: JClass,
) {
    log::info!("IME Stop Recording");
    let (buffer, engine_arc_opt, jvm, service_ref) = {
        let mut state_guard = IME_STATE.lock().unwrap();
        if let Some(state) = state_guard.as_mut() {
            state.stream = None; // Drop stream stops recording
            
            let buf = state.audio_buffer.lock().unwrap().clone();
            let eng = state.engine.clone(); // Clone the Arc
            
            (buf, eng, state.jvm.clone(), state.service_ref.clone())
        } else {
            return;
        }
    };
    
    if engine_arc_opt.is_none() {
         send_ime_status(&mut env, service_ref.as_obj(), "Engine not ready");
         return;
    }
    let engine_arc = engine_arc_opt.unwrap();
    
    send_ime_status(&mut env, service_ref.as_obj(), "Transcribing...");
    
    std::thread::spawn(move || {
        let mut env = jvm.attach_current_thread().unwrap();
        let service_obj = service_ref.as_obj();
        
        let transcription_result = {
            // Lock ONLY the engine, not the global state
            let mut engine = engine_arc.lock().unwrap();
            engine.transcribe_samples(buffer, None)
                .map_err(|e| anyhow::anyhow!(e.to_string()))
        };
        
        match transcription_result {
            Ok(res) => {
                send_ime_status(&mut env, &service_obj, "Ready");
                let text_j = env.new_string(res.text).unwrap();
                let _ = env.call_method(service_obj, "onTextTranscribed", "(Ljava/lang/String;)V", &[(&text_j).into()]);
            },
            Err(e) => {
                send_ime_status(&mut env, &service_obj, &format!("Error: {}", e));
            }
        }
    });
}