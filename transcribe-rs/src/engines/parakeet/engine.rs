//! Parakeet (NeMo) speech recognition engine implementation.
//!
//! This module provides a Parakeet-based transcription engine that uses
//! NVIDIA's NeMo Parakeet models for speech-to-text conversion. Parakeet models
//! are provided as directory structures containing model files.
//!
//! # Model Format
//!
//! Parakeet expects a directory containing the model files, typically structured like:
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
//! # Examples
//!
//! ## Basic Usage with FP32
//!
//! ```rust,no_run
//! use transcribe_rs::{TranscriptionEngine, engines::parakeet::{ParakeetEngine, ParakeetModelParams}};
//! use std::path::PathBuf;
//!
//! let mut engine = ParakeetEngine::new();
//! engine.load_model_with_params(
//!     &PathBuf::from("models/parakeet-v0.3"),
//!     ParakeetModelParams::fp32()
//! )?;
//!
//! let result = engine.transcribe_file(&PathBuf::from("audio.wav"), None)?;
//! println!("Transcription: {}", result.text);
//! # Ok::<(), Box<dyn std::error::Error>>(())
//! ```
//!
//! ## With Int8 Quantization
//!
//! ```rust,no_run
//! use transcribe_rs::{TranscriptionEngine, engines::parakeet::{ParakeetEngine, ParakeetModelParams}};
//! use std::path::PathBuf;
//!
//! let mut engine = ParakeetEngine::new();
//! engine.load_model_with_params(
//!     &PathBuf::from("models/parakeet-v0.3"),
//!     ParakeetModelParams::int8()  // Use quantized model for faster inference
//! )?;
//!
//! let result = engine.transcribe_file(&PathBuf::from("audio.wav"), None)?;
//! println!("Transcription: {}", result.text);
//! # Ok::<(), Box<dyn std::error::Error>>(())
//! ```
//!
//! ## With Custom Timestamp Granularity
//!
//! ```rust,no_run
//! use transcribe_rs::{TranscriptionEngine, engines::parakeet::{ParakeetEngine, ParakeetInferenceParams, TimestampGranularity}};
//! use std::path::PathBuf;
//!
//! let mut engine = ParakeetEngine::new();
//! engine.load_model(&PathBuf::from("models/parakeet-v0.3"))?;
//!
//! let params = ParakeetInferenceParams {
//!     timestamp_granularity: TimestampGranularity::Word,  // Get word-level timestamps
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

use crate::{
    engines::parakeet::{model::ParakeetModel, timestamps::convert_timestamps},
    TranscriptionEngine, TranscriptionResult,
};
use std::path::{Path, PathBuf};

/// Granularity level for timestamp generation.
///
/// Controls the level of detail in the timing information returned
/// by the Parakeet engine.
#[derive(Debug, Clone, Default, PartialEq)]
pub enum TimestampGranularity {
    /// Token-level timestamps (most detailed, default)
    #[default]
    Token,
    /// Word-level timestamps (grouped tokens into words)
    Word,
    /// Segment-level timestamps (larger phrases/sentences)
    Segment,
}

/// Quantization type for Parakeet model loading.
///
/// Controls the precision/performance trade-off for the loaded model.
/// Int8 quantization provides faster inference at the cost of some accuracy.
#[derive(Debug, Clone, Default, PartialEq)]
pub enum QuantizationType {
    /// Full precision (32-bit floating point, default)
    #[default]
    FP32,
    /// 8-bit integer quantization (faster, slightly lower accuracy)
    Int8,
}

/// Parameters for configuring Parakeet model loading.
///
/// Controls model quantization settings for balancing performance vs accuracy.
#[derive(Debug, Clone, Default)]
pub struct ParakeetModelParams {
    /// The quantization type to use for the model
    pub quantization: QuantizationType,
}

impl ParakeetModelParams {
    /// Create parameters for full precision (FP32) model loading.
    ///
    /// Provides the highest accuracy but slower inference speed.
    ///
    /// # Examples
    ///
    /// ```rust
    /// use transcribe_rs::engines::parakeet::ParakeetModelParams;
    ///
    /// let params = ParakeetModelParams::fp32();
    /// ```
    pub fn fp32() -> Self {
        Self {
            quantization: QuantizationType::FP32,
        }
    }

    /// Create parameters for Int8 quantized model loading.
    ///
    /// Provides faster inference speed with slightly reduced accuracy.
    ///
    /// # Examples
    ///
    /// ```rust
    /// use transcribe_rs::engines::parakeet::ParakeetModelParams;
    ///
    /// let params = ParakeetModelParams::int8();
    /// ```
    pub fn int8() -> Self {
        Self {
            quantization: QuantizationType::Int8,
        }
    }

    /// Create parameters with a specific quantization type.
    ///
    /// # Arguments
    ///
    /// * `quantization` - The quantization type to use
    ///
    /// # Examples
    ///
    /// ```rust
    /// use transcribe_rs::engines::parakeet::{ParakeetModelParams, QuantizationType};
    ///
    /// let params = ParakeetModelParams::quantized(QuantizationType::Int8);
    /// ```
    pub fn quantized(quantization: QuantizationType) -> Self {
        Self { quantization }
    }
}

/// Parameters for configuring Parakeet inference behavior.
///
/// Controls the level of detail in timestamp generation and other
/// inference-specific settings.
#[derive(Debug, Clone)]
pub struct ParakeetInferenceParams {
    /// The granularity level for timestamp generation
    pub timestamp_granularity: TimestampGranularity,
}

impl Default for ParakeetInferenceParams {
    fn default() -> Self {
        Self {
            timestamp_granularity: TimestampGranularity::Token,
        }
    }
}

/// Parakeet speech recognition engine.
///
/// This engine uses NVIDIA's NeMo Parakeet models for speech-to-text transcription.
/// It supports quantization and flexible timestamp granularity options.
///
/// # Model Requirements
///
/// - **Format**: Directory containing model files
/// - **Structure**: Must contain tokenizer, config, and weight files
/// - **Quantization**: Supports both FP32 and Int8 quantized models
///
/// # Examples
///
/// ```rust,no_run
/// use transcribe_rs::engines::parakeet::ParakeetEngine;
///
/// let mut engine = ParakeetEngine::new();
/// // Engine is ready to load a model directory
/// ```
pub struct ParakeetEngine {
    loaded_model_path: Option<PathBuf>,
    model: Option<ParakeetModel>,
}

impl Default for ParakeetEngine {
    fn default() -> Self {
        Self::new()
    }
}

impl ParakeetEngine {
    /// Create a new Parakeet engine instance.
    ///
    /// The engine starts unloaded - you must call `load_model()` or
    /// `load_model_with_params()` before performing transcription operations.
    ///
    /// # Examples
    ///
    /// ```rust
    /// use transcribe_rs::engines::parakeet::ParakeetEngine;
    ///
    /// let engine = ParakeetEngine::new();
    /// // Engine is ready to load a model directory
    /// ```
    pub fn new() -> Self {
        Self {
            loaded_model_path: None,
            model: None,
        }
    }
}

impl Drop for ParakeetEngine {
    fn drop(&mut self) {
        self.unload_model();
    }
}

impl TranscriptionEngine for ParakeetEngine {
    type InferenceParams = ParakeetInferenceParams;
    type ModelParams = ParakeetModelParams;

    fn load_model_with_params(
        &mut self,
        model_path: &Path,
        params: Self::ModelParams,
    ) -> Result<(), Box<dyn std::error::Error>> {
        let quantized = match params.quantization {
            QuantizationType::FP32 => false,
            QuantizationType::Int8 => true,
        };
        let model = ParakeetModel::new(model_path, quantized)?;

        self.model = Some(model);
        self.loaded_model_path = Some(model_path.to_path_buf());
        Ok(())
    }

    fn unload_model(&mut self) {
        self.loaded_model_path = None;
        self.model = None;
    }

    fn transcribe_samples(
        &mut self,
        samples: Vec<f32>,
        params: Option<Self::InferenceParams>,
    ) -> Result<TranscriptionResult, Box<dyn std::error::Error>> {
        let model: &mut ParakeetModel = self
            .model
            .as_mut()
            .ok_or("Model not loaded. Call load_model() first.")?;

        let parakeet_params = params.unwrap_or_default();

        // Get the timestamped result from the model
        let timestamped_result = model.transcribe_samples(samples)?;

        // Convert timestamps based on requested granularity
        let segments =
            convert_timestamps(&timestamped_result, parakeet_params.timestamp_granularity);

        Ok(TranscriptionResult {
            text: timestamped_result.text,
            segments: Some(segments),
        })
    }
}
