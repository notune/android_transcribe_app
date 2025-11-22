use once_cell::sync::Lazy;
use std::path::PathBuf;
use std::sync::Mutex;
use transcribe_rs::engines::whisper::{WhisperEngine, WhisperInferenceParams};
use transcribe_rs::TranscriptionEngine;

// Shared model loaded once for all tests
static MODEL_ENGINE: Lazy<Mutex<WhisperEngine>> = Lazy::new(|| {
    let mut engine = WhisperEngine::new();
    let model_path = PathBuf::from("models/whisper-medium-q4_1.bin");
    engine
        .load_model(&model_path)
        .expect("Failed to load model");
    Mutex::new(engine)
});

fn get_engine() -> std::sync::MutexGuard<'static, WhisperEngine> {
    MODEL_ENGINE.lock().expect("Failed to lock engine")
}

#[test]
fn test_jfk_transcription() {
    let mut engine = get_engine();

    // Load the JFK audio file
    let audio_path = PathBuf::from("samples/jfk.wav");

    // Transcribe with default params
    let result = engine
        .transcribe_file(&audio_path, None)
        .expect("Failed to transcribe");

    let expected = "And so my fellow Americans, ask not what your country can do for you, ask what you can do for your country.";
    assert_eq!(
        result.text.trim(),
        expected,
        "\nExpected: '{}'\nActual: '{}'",
        expected,
        result.text.trim()
    );
}

#[test]
fn test_prompt_product_names() {
    let mut engine = get_engine();

    let audio_path = PathBuf::from("samples/product_names.wav");

    let baseline_expected = "Welcome to Quirk, Quid, Quill, Inc. where finance meets innovation explore diverse offerings. From the P3 Quatro, a unique investment portfolio quadrant to the O3 Omni, a platform for intricate derivative trading strategies. Delve into unconventional bond markets with our D3 Bond X and experience non-standard equity trading with E3 Equity. Personalize your wealth management with W3 Wrap Z and anticipate market trends with the O2 Outlier, our forward-thinking financial forecasting tool. Explore venture capital world with U3 Unifund or move your money with the M3 Mover, our sophisticated monetary transfer module. At Quirk, Quid, Quill, Inc. we turn complex finance into creative solutions. Join us in redefining financial services.";
    let prompted_expected = "Welcome to QuirkQuid Quill Inc, where finance meets innovation. Explore diverse offerings, from the P3-Quattro, a unique investment portfolio quadrant, to the O3-Omni, a platform for intricate derivative trading strategies. Delve into unconventional bond markets with our B3-BondX and experience non-standard equity trading with E3-Equity. Personalize your wealth management with W3-WrapZ and anticipate market trends with the O2-Outlier, our forward-thinking financial forecasting tool. Explore venture capital world with U3-Unifund or move your money with the M3-Mover, our sophisticated monetary transfer module. At QuirkQuid Quill Inc, we turn complex finance into creative solutions. Join us in redefining financial services.";

    // Baseline transcription with no prompt - expected to have misspellings
    let baseline_result = engine
        .transcribe_file(&audio_path, None)
        .expect("Failed to transcribe without prompt");

    println!("\n=== Baseline Transcription (no prompt) ===");
    println!("{}", baseline_result.text);

    assert_eq!(baseline_result.text, baseline_expected);

    let glossary_prompt = "QuirkQuid Quill Inc, P3-Quattro, O3-Omni, B3-BondX, E3-Equity, W3-WrapZ, O2-Outlier, U3-UniFund, M3-Mover";
    let params = WhisperInferenceParams {
        initial_prompt: Some(glossary_prompt.to_string()),
        ..Default::default()
    };
    let prompted_result = engine
        .transcribe_file(&audio_path, Some(params))
        .expect("Failed to transcribe with prompt");
    println!("\n=== Transcription with Glossary Prompt ===");
    println!("{}", prompted_result.text);

    assert_eq!(prompted_result.text, prompted_expected);
}
