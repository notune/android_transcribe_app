use std::path::PathBuf;
use std::time::Instant;

use transcribe_rs::{
    engines::{
        parakeet::{
            ParakeetEngine, ParakeetInferenceParams, ParakeetModelParams, TimestampGranularity,
        },
        whisper::{WhisperEngine, WhisperInferenceParams},
    },
    TranscriptionEngine,
};

fn get_audio_duration(path: &PathBuf) -> Result<f64, Box<dyn std::error::Error>> {
    let reader = hound::WavReader::open(path)?;
    let spec = reader.spec();
    let duration = reader.duration() as f64 / spec.sample_rate as f64;
    Ok(duration)
}

enum Engine {
    Whisper,
    Parakeet,
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    // Initialize logger
    env_logger::init();

    // Switch between engines here
    let engine_type = Engine::Parakeet; // Change to Engine::Parakeet to use Parakeet

    let wav_path = PathBuf::from("samples/dots.wav");

    // Get audio duration
    let audio_duration = get_audio_duration(&wav_path)?;
    println!("Audio duration: {:.2}s", audio_duration);

    match engine_type {
        Engine::Whisper => {
            let mut engine = WhisperEngine::new();
            let model_path = PathBuf::from("models/whisper-medium-q4_1.bin");

            println!("Using Whisper engine");
            println!("Loading model: {:?}", model_path);

            let load_start = Instant::now();
            engine.load_model(&model_path)?;
            let load_duration = load_start.elapsed();
            println!("Model loaded in {:.2?}", load_duration);

            println!("Transcribing file: {:?}", wav_path);
            let transcribe_start = Instant::now();
            
            // Example of using initial_prompt to provide context
            let params = WhisperInferenceParams {
                initial_prompt: Some("This is a conversation about technology and AI.".to_string()),
                ..Default::default()
            };
            
            let result = engine.transcribe_file(&wav_path, Some(params))?;
            let transcribe_duration = transcribe_start.elapsed();
            println!("Transcription completed in {:.2?}", transcribe_duration);

            // Calculate real-time speedup factor
            let speedup_factor = audio_duration / transcribe_duration.as_secs_f64();
            println!(
                "Real-time speedup: {:.2}x faster than real-time",
                speedup_factor
            );

            println!("Transcription result:");
            println!("{}", result.text);

            if let Some(segments) = result.segments {
                println!("\nSegments:");
                for segment in segments {
                    println!(
                        "[{:.2}s - {:.2}s]: {}",
                        segment.start, segment.end, segment.text
                    );
                }
            }

            engine.unload_model();
        }
        Engine::Parakeet => {
            let mut engine = ParakeetEngine::new();
            let model_path = PathBuf::from("models/parakeet-tdt-0.6b-v3-int8");

            println!("Using Parakeet engine");
            println!("Loading model: {:?}", model_path);

            let load_start = Instant::now();
            engine.load_model_with_params(&model_path, ParakeetModelParams::int8())?;
            let load_duration = load_start.elapsed();
            println!("Model loaded in {:.2?}", load_duration);

            println!("Transcribing file: {:?}", wav_path);
            let transcribe_start = Instant::now();

            // Configure Parakeet parameters with timestamp granularity
            let params = ParakeetInferenceParams {
                timestamp_granularity: TimestampGranularity::Segment, // Options: Token, Word, Segment
                ..Default::default()
            };

            let result = engine.transcribe_file(&wav_path, Some(params))?;
            let transcribe_duration = transcribe_start.elapsed();
            println!("Transcription completed in {:.2?}", transcribe_duration);

            // Calculate real-time speedup factor
            let speedup_factor = audio_duration / transcribe_duration.as_secs_f64();
            println!(
                "Real-time speedup: {:.2}x faster than real-time",
                speedup_factor
            );

            println!("Transcription result:");
            println!("{}", result.text);

            if let Some(segments) = result.segments {
                println!("\nSegments:");
                for segment in segments {
                    println!(
                        "[{:.2}s - {:.2}s]: {}",
                        segment.start, segment.end, segment.text
                    );
                }
            }

            engine.unload_model();
        }
    }

    Ok(())
}
