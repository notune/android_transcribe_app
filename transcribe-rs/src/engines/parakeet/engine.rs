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
use once_cell::sync::Lazy;
use regex::Regex;
use text2num::{replace_numbers_in_text, Language};

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

// Matches alphabetic tokens (including German umlauts) for lightweight tokenization.
// We intentionally keep punctuation and whitespace outside this regex.
static WORD_TOKEN_RE: Lazy<Regex> = Lazy::new(|| {
    Regex::new(r"(?iu)[[:alpha:]äöüß]+").expect("valid word token regex")
});

static GERMAN_UHR_TO_COLON_RE: Lazy<Regex> = Lazy::new(|| {
    Regex::new(r"(?i)\b(\d{1,2})\s*uhr\s*(\d{1,2})\b").expect("valid uhr-to-colon regex")
});

static GERMAN_MONTH_RE: Lazy<Regex> = Lazy::new(|| {
    Regex::new(
        r"(?ix)\b(
            januar|februar|maerz|märz|april|mai|juni|juli|august|
            september|oktober|november|dezember
        )\b"
    )
    .expect("valid german month regex")
});

static GERMAN_UHR_LOCAL_RE: Lazy<Regex> = Lazy::new(|| {
    Regex::new(r"(?i)\b([[:alpha:]0-9äöüß\.\-]+)\s+uhr\s+([[:alpha:]0-9äöüß\.\-]+)\b")
        .expect("valid local uhr regex")
});

static GERMAN_DOTTED_UHR_TIME_RE: Lazy<Regex> = Lazy::new(|| {
    Regex::new(r"(?i)\b(\d{1,2})\.(\d{1,2})\s*uhr\b").expect("valid dotted uhr time regex")
});

static GERMAN_MONTH_LOCAL_RE: Lazy<Regex> = Lazy::new(|| {
    Regex::new(
        r"(?ix)\b((?:am|den)\s+)?([[:alpha:]0-9äöüß\.\-]+)\s+
        (januar|februar|maerz|märz|april|mai|juni|juli|august|september|oktober|november|dezember)\b"
    )
    .expect("valid local month regex")
});

fn normalize_dotted_uhr_time(text: &str) -> String {
    GERMAN_DOTTED_UHR_TIME_RE
        .replace_all(text, |caps: &regex::Captures| {
            let hour = caps.get(1).map(|m| m.as_str()).unwrap_or("0");
            let minute = caps
                .get(2)
                .map(|m| m.as_str())
                .and_then(|s| s.parse::<u32>().ok())
                .unwrap_or(0);

            format!("{hour}:{minute:02} Uhr")
        })
        .into_owned()
}

fn normalize_uhr_to_colon(text: &str) -> String {
    GERMAN_UHR_TO_COLON_RE
        .replace_all(text, |caps: &regex::Captures| {
            let h = caps.get(1).map(|m| m.as_str()).unwrap_or("0");
            let m = caps
                .get(2)
                .map(|m| m.as_str())
                .and_then(|s| s.parse::<u32>().ok())
                .unwrap_or(0);
            format!("{h}:{m:02}")
        })
        .into_owned()
}

// German number word fragments used for joining ASR-split number expressions.
// The goal is to merge whitespace between adjacent number fragments, e.g.
// "Neunzehnhundert siebenundvierzig" -> "Neunzehnhundertsiebenundvierzig".
fn is_german_number_fragment(token: &str) -> bool {
    let t = token.to_lowercase();

    matches!(
        t.as_str(),
        // basic numbers
        "null"
            | "eins"
            | "ein"
            | "eine"
            | "einen"
            | "einem"
            | "einer"
            | "zwei"
            | "drei"
            | "vier"
            | "fuenf"
            | "fünf"
            | "sechs"
            | "sieben"
            | "acht"
            | "neun"
            | "zehn"
            | "elf"
            | "zwoelf"
            | "zwölf"
            // teens
            | "dreizehn"
            | "vierzehn"
            | "fuenfzehn"
            | "fünfzehn"
            | "sechzehn"
            | "siebzehn"
            | "achtzehn"
            | "neunzehn"
            // tens
            | "zwanzig"
            | "dreissig"
            | "dreißig"
            | "vierzig"
            | "fuenfzig"
            | "fünfzig"
            | "sechzig"
            | "siebzig"
            | "achtzig"
            | "neunzig"
            // scale words
            | "hundert"
            | "tausend"
            | "million"
            | "millionen"
            | "milliarde"
            | "milliarden"
    ) || t.contains("und")
        || t.contains("hundert")
        || t.contains("tausend")
}

fn is_german_scale_fragment(token: &str) -> bool {
    let t = token.to_lowercase();

    t == "hundert"
        || t == "tausend"
        || t == "million"
        || t == "millionen"
        || t == "milliarde"
        || t == "milliarden"
        || t.contains("hundert")
        || t.contains("tausend")
}

// Joins whitespace between adjacent German number fragments while preserving
// punctuation and all non-number text unchanged.
//
// Examples:
// - "Neunzehnhundert elf" -> "Neunzehnhundertelf"
// - "Neunzehnhundert siebenundvierzig" -> "Neunzehnhundertsiebenundvierzig"
// - "Siebenundvierzig Elf" -> "SiebenundvierzigElf"
fn join_split_german_number_words(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut cursor = 0usize;

    let mut pending_ws: Option<String> = None;
    let mut prev_was_number_fragment = false;
    let mut prev_word_token: Option<String> = None;

    for m in WORD_TOKEN_RE.find_iter(text) {
        let start = m.start();
        let end = m.end();
        let token = m.as_str();

        // Emit any text before this token (punctuation/whitespace/etc.).
        // We treat pure whitespace specially and may suppress it later if the
        // surrounding tokens are number fragments.
        if start > cursor {
            let between = &text[cursor..start];
            if between.chars().all(char::is_whitespace) {
                pending_ws = Some(between.to_string());
            } else {
                if let Some(ws) = pending_ws.take() {
                    out.push_str(&ws);
                }
                out.push_str(between);
                prev_was_number_fragment = false;
				prev_word_token = None;
            }
        }

        let current_is_number_fragment = is_german_number_fragment(token);

		if let Some(ws) = pending_ws.take() {
			let should_join = if prev_was_number_fragment && current_is_number_fragment {
				let prev_has_scale = prev_word_token
					.as_deref()
					.map(is_german_scale_fragment)
					.unwrap_or(false);
				let curr_has_scale = is_german_scale_fragment(token);

				// Join only if at least one side contains a German scale marker
				// (hundert/tausend/million/...). This keeps separate numbers like
				// "siebenundvierzig elf" apart, but still fixes ASR splits like
				// "neunzehnhundert siebenundvierzig".
				prev_has_scale || curr_has_scale
			} else {
				false
			};

			if !should_join {
				out.push_str(&ws);
			}
		}

        out.push_str(token);
        prev_was_number_fragment = current_is_number_fragment;
		prev_word_token = Some(token.to_string());
        cursor = end;
    }

    // Emit trailing remainder.
    if cursor < text.len() {
        if let Some(ws) = pending_ws.take() {
            out.push_str(&ws);
        }
        out.push_str(&text[cursor..]);
    } else if let Some(ws) = pending_ws.take() {
        out.push_str(&ws);
    }

    out
}

fn normalize_german_numbers_conservative(text: &str) -> String {
    if text.trim().is_empty() {
        return text.to_string();
    }

    // First, merge ASR-split German number words (common with Parakeet output).
    let joined = join_split_german_number_words(text);

    let de = Language::german();

    // Default behavior: keep isolated 1..12 as words, but convert larger numbers.
    let mut normalized = replace_numbers_in_text(&joined, &de, 13.0);

    // Only normalize small numbers locally in time context ("... uhr ..."),
    // not in the entire sentence.
    if normalized.to_lowercase().contains("uhr") {
        normalized = GERMAN_UHR_LOCAL_RE
            .replace_all(&normalized, |caps: &regex::Captures| {
                let full = caps.get(0).map(|m| m.as_str()).unwrap_or_default();
                replace_numbers_in_text(full, &de, 0.0)
            })
            .into_owned();
    }

    // Same idea for month/date context ("dritten märz", "am dritten märz", ...).
    if GERMAN_MONTH_RE.is_match(&normalized) {
        normalized = GERMAN_MONTH_LOCAL_RE
            .replace_all(&normalized, |caps: &regex::Captures| {
                let full = caps.get(0).map(|m| m.as_str()).unwrap_or_default();
                replace_numbers_in_text(full, &de, 0.0)
            })
            .into_owned();
    }

    normalize_dotted_uhr_time(&normalize_uhr_to_colon(&normalized))
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
		let mut segments =
			convert_timestamps(&timestamped_result, parakeet_params.timestamp_granularity);

		// Apply conservative German number normalization to Parakeet output.
		// - Converts number words > 12 to digits
		// - Keeps isolated 1..12 as words
		// - Fixes common ASR split-number spacing before conversion
		let text = normalize_german_numbers_conservative(&timestamped_result.text);

		// Keep segment text aligned with the same normalization logic (best-effort).
		for segment in &mut segments {
			segment.text = normalize_german_numbers_conservative(&segment.text);
		}

		Ok(TranscriptionResult {
			text,
			segments: Some(segments),
		})
    }
}
