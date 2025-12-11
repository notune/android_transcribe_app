//! Speech recognition engines for transcription.
//!
//! This module contains implementations of different speech recognition engines
//! that can be used for audio transcription. Each engine has its own requirements
//! for model formats and provides different capabilities.
//!
//! # Available Engines
//!
//! ## Whisper Engine
//!
//! OpenAI's Whisper model implementation:
//! - **Model Format**: Single GGML format file (`.bin`)
//! - **Models**: tiny, base, small, medium, large variants
//! - **Features**: Multi-language support, robust performance
//! - **Example**: `whisper-medium-q4_1.bin`
//!
//! ## Parakeet Engine
//!
//! NVIDIA NeMo Parakeet model implementation:
//! - **Model Format**: Directory containing model files
//! - **Features**: Flexible timestamp granularity, quantization support
//! - **Performance**: Optimized for speed with Int8 quantization
//! - **Example**: `parakeet-v0.3/` directory
//!
//! # Usage Comparison
//!
//! ```rust,no_run
//! use transcribe_rs::{TranscriptionEngine, engines::{whisper::WhisperEngine, parakeet::ParakeetEngine}};
//! use std::path::PathBuf;
//!
//! // Whisper: Single file model
//! let mut whisper = WhisperEngine::new();
//! whisper.load_model(&PathBuf::from("models/whisper-medium-q4_1.bin"))?;
//!
//! // Parakeet: Directory model
//! let mut parakeet = ParakeetEngine::new();
//! parakeet.load_model(&PathBuf::from("models/parakeet-v0.3"))?;
//!
//! // Both engines implement the same TranscriptionEngine trait
//! let whisper_result = whisper.transcribe_file(&PathBuf::from("audio.wav"), None)?;
//! let parakeet_result = parakeet.transcribe_file(&PathBuf::from("audio.wav"), None)?;
//! # Ok::<(), Box<dyn std::error::Error>>(())
//! ```

pub mod parakeet;
#[cfg(not(target_os = "android"))]
pub mod whisper;
