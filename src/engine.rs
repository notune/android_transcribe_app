use std::sync::{Arc, Mutex};
use transcribe_rs::engines::parakeet::ParakeetEngine;
use once_cell::sync::Lazy;

pub static GLOBAL_ENGINE: Lazy<Mutex<Option<Arc<Mutex<ParakeetEngine>>>>> = Lazy::new(|| Mutex::new(None));

pub fn get_engine() -> Option<Arc<Mutex<ParakeetEngine>>> {
    GLOBAL_ENGINE.lock().unwrap().clone()
}

pub fn set_engine(engine: ParakeetEngine) {
    *GLOBAL_ENGINE.lock().unwrap() = Some(Arc::new(Mutex::new(engine)));
}

pub fn is_engine_loaded() -> bool {
    GLOBAL_ENGINE.lock().unwrap().is_some()
}
