//! # transcribe-rs
//!
//! A Rust library providing unified transcription capabilities using multiple speech recognition engines.
//! Currently supports Whisper and Parakeet (NeMo) models for accurate speech-to-text transcription.
//!
//! ## Features
//!
//! - **Multiple Engines**: Support for both Whisper and Parakeet transcription engines
//! - **Flexible Model Loading**: Load models with custom parameters (quantization, etc.)
//! - **Timestamped Results**: Get detailed timing information for transcribed segments
//! - **Audio Processing**: Built-in WAV file processing with proper format validation
//! - **Unified API**: Common trait-based interface for all transcription engines
//!
//! ## Model Format Requirements
//!
//! - **Whisper**: Expects a single GGML format file (e.g., `whisper-medium-q4_1.bin`)
//! - **Parakeet**: Expects a directory containing the model files (e.g., `parakeet-v0.3/`)
//!
//! ## Quick Start
//!
//! ```rust,no_run
//! use std::path::PathBuf;
//! use transcribe_rs::{engines::whisper::WhisperEngine, TranscriptionEngine};
//!
//! let mut engine = WhisperEngine::new();
//! engine.load_model(&PathBuf::from("models/whisper-medium-q4_1.bin"))?;
//!
//! let result = engine.transcribe_file(&PathBuf::from("audio.wav"), None)?;
//! println!("Transcription: {}", result.text);
//!
//! if let Some(segments) = result.segments {
//!     for segment in segments {
//!         println!(
//!             "[{:.2}s - {:.2}s]: {}",
//!             segment.start, segment.end, segment.text
//!         );
//!     }
//! }
//! # Ok::<(), Box<dyn std::error::Error>>(())
//! ```
//!
//! ## Audio Requirements
//!
//! Input audio files must be:
//! - WAV format
//! - 16 kHz sample rate
//! - 16-bit samples
//! - Mono (single channel)

pub mod audio;
pub mod engines;

pub mod remote;
pub use remote::RemoteTranscriptionEngine;

use std::path::Path;

/// The result of a transcription operation.
///
/// Contains both the full transcribed text and detailed timing information
/// for individual segments within the audio.
#[derive(Debug)]
pub struct TranscriptionResult {
    /// The complete transcribed text from the audio
    pub text: String,
    /// Individual segments with timing information
    pub segments: Option<Vec<TranscriptionSegment>>,
}

/// A single transcribed segment with timing information.
///
/// Represents a portion of the transcribed audio with start and end timestamps
/// and the corresponding text content.
#[derive(Debug)]
pub struct TranscriptionSegment {
    /// Start time of the segment in seconds
    pub start: f32,
    /// End time of the segment in seconds
    pub end: f32,
    /// The transcribed text for this segment
    pub text: String,
}

/// Common interface for speech transcription engines.
///
/// This trait defines the standard operations that all transcription engines must support.
/// Each engine may have different parameter types for model loading and inference configuration.
///
/// # Examples
///
/// ## Using Whisper Engine
///
/// ```rust,no_run
/// use std::path::PathBuf;
/// use transcribe_rs::{engines::whisper::WhisperEngine, TranscriptionEngine};
///
/// let mut engine = WhisperEngine::new();
/// engine.load_model(&PathBuf::from("models/whisper-medium-q4_1.bin"))?;
///
/// let result = engine.transcribe_file(&PathBuf::from("audio.wav"), None)?;
/// println!("Transcription: {}", result.text);
/// # Ok::<(), Box<dyn std::error::Error>>(())
/// ```
///
/// ## Using Parakeet Engine
///
/// ```rust,no_run
/// use std::path::PathBuf;
/// use transcribe_rs::{
///     engines::parakeet::{ParakeetEngine, ParakeetModelParams},
///     TranscriptionEngine,
/// };
///
/// let mut engine = ParakeetEngine::new();
/// engine.load_model_with_params(
///     &PathBuf::from("models/parakeet-v0.3"),
///     ParakeetModelParams::int8(),
/// )?;
///
/// let result = engine.transcribe_file(&PathBuf::from("audio.wav"), None)?;
/// println!("Transcription: {}", result.text);
/// # Ok::<(), Box<dyn std::error::Error>>(())
/// ```
pub trait TranscriptionEngine {
    /// Parameters for configuring inference behavior (language, timestamps, etc.)
    type InferenceParams;
    /// Parameters for configuring model loading (quantization, etc.)
    type ModelParams: Default;

    /// Load a model from the specified path using default parameters.
    ///
    /// # Arguments
    ///
    /// * `model_path` - Path to the model file or directory
    ///
    /// # Returns
    ///
    /// Returns `Ok(())` if the model loads successfully, or an error if loading fails.
    fn load_model(&mut self, model_path: &Path) -> Result<(), Box<dyn std::error::Error>> {
        self.load_model_with_params(model_path, Self::ModelParams::default())
    }

    /// Load a model from the specified path with custom parameters.
    ///
    /// # Arguments
    ///
    /// * `model_path` - Path to the model file or directory
    /// * `params` - Engine-specific model loading parameters
    ///
    /// # Returns
    ///
    /// Returns `Ok(())` if the model loads successfully, or an error if loading fails.
    fn load_model_with_params(
        &mut self,
        model_path: &Path,
        params: Self::ModelParams,
    ) -> Result<(), Box<dyn std::error::Error>>;

    /// Unload the currently loaded model and free associated resources.
    fn unload_model(&mut self);

    /// Transcribe audio samples directly.
    ///
    /// # Arguments
    ///
    /// * `samples` - Audio samples as f32 values (16kHz, mono)
    /// * `params` - Optional engine-specific inference parameters
    ///
    /// # Returns
    ///
    /// Returns transcription result with text and timing information.
    fn transcribe_samples(
        &mut self,
        samples: Vec<f32>,
        params: Option<Self::InferenceParams>,
    ) -> Result<TranscriptionResult, Box<dyn std::error::Error>>;

    /// Transcribe audio from a WAV file.
    ///
    /// The WAV file must meet the following requirements:
    /// - 16 kHz sample rate
    /// - 16-bit samples
    /// - Mono (single channel)
    /// - PCM format
    ///
    /// # Arguments
    ///
    /// * `wav_path` - Path to the WAV file to transcribe
    /// * `params` - Optional engine-specific inference parameters
    ///
    /// # Returns
    ///
    /// Returns transcription result with text and timing information.
    fn transcribe_file(
        &mut self,
        wav_path: &Path,
        params: Option<Self::InferenceParams>,
    ) -> Result<TranscriptionResult, Box<dyn std::error::Error>> {
        let samples = audio::read_wav_samples(wav_path)?;
        self.transcribe_samples(samples, params)
    }
}
