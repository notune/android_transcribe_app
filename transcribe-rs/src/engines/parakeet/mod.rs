//! Parakeet (NeMo) speech recognition engine.
//!
//! This module provides a complete implementation of NVIDIA's NeMo Parakeet
//! speech recognition model, including timestamp processing and quantization support.
//!
//! # Key Features
//!
//! - **Quantization Support**: FP32 and Int8 quantized models
//! - **Flexible Timestamps**: Token, word, and segment-level timing
//! - **High Performance**: Optimized for real-time transcription
//! - **Directory Models**: Uses model directories rather than single files
//!
//! # Model Structure
//!
//! Parakeet models are organized as directories containing:
//! ```text
//! parakeet-v0.3/
//! ├── encoder-model.onnx           # Encoder model (FP32)
//! ├── encoder-model.int8.onnx      # Encoder model (Int8 quantized)
//! ├── decoder_joint-model.onnx    # Decoder/joint model (FP32)
//! ├── decoder_joint-model.int8.onnx # Decoder/joint model (Int8 quantized)
//! ├── nemo128.onnx                 # Audio preprocessor
//! ├── vocab.txt                    # Vocabulary file
//! └── config.json                  # Model configuration
//! ```
//!
//! # Usage Examples
//!
//! ## Basic Transcription
//!
//! ```rust,no_run
//! use transcribe_rs::{TranscriptionEngine, engines::parakeet::ParakeetEngine};
//! use std::path::PathBuf;
//!
//! let mut engine = ParakeetEngine::new();
//! engine.load_model(&PathBuf::from("models/parakeet-v0.3"))?;
//!
//! let result = engine.transcribe_file(&PathBuf::from("audio.wav"), None)?;
//! println!("Transcription: {}", result.text);
//! # Ok::<(), Box<dyn std::error::Error>>(())
//! ```
//!
//! ## With Quantization and Custom Timestamps
//!
//! ```rust,no_run
//! use transcribe_rs::{
//!     TranscriptionEngine,
//!     engines::parakeet::{ParakeetEngine, ParakeetModelParams, ParakeetInferenceParams, TimestampGranularity}
//! };
//! use std::path::PathBuf;
//!
//! let mut engine = ParakeetEngine::new();
//!
//! // Load with Int8 quantization for faster inference
//! engine.load_model_with_params(
//!     &PathBuf::from("models/parakeet-v0.3"),
//!     ParakeetModelParams::int8()
//! )?;
//!
//! // Configure for word-level timestamps
//! let params = ParakeetInferenceParams {
//!     timestamp_granularity: TimestampGranularity::Word,
//! };
//!
//! let result = engine.transcribe_file(&PathBuf::from("audio.wav"), Some(params))?;
//!
//! if let Some(segments) = result.segments {
//!     for segment in segments {
//!         println!("[{:.2}s - {:.2}s]: {}", segment.start, segment.end, segment.text);
//!     }
//! }
//! # Ok::<(), Box<dyn std::error::Error>>(())
//! ```

pub mod engine;
pub mod model;
pub mod timestamps;

pub use engine::{
    ParakeetEngine, ParakeetInferenceParams, ParakeetModelParams, QuantizationType,
    TimestampGranularity,
};
pub use model::{ParakeetError, ParakeetModel, TimestampedResult};
pub use timestamps::{convert_timestamps, WordBoundary};
