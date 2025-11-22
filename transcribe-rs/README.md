# transcribe-rs

A Rust library for audio transcription supporting multiple engines including Whisper and Parakeet.

This library was extracted from the [Handy](https://github.com/cjpais/handy) project to help other developers integrate transcription capabilities into their applications. We hope to support additional ASR models in the future and may expand to include features like microphone input and real-time transcription.

## Features

- **Multiple Transcription Engines**: Support for both Whisper and Parakeet models
- **Cross-platform**: Works on macOS, Windows, and Linux with optimized backends
- **Hardware Acceleration**: Metal on macOS, Vulkan on Windows/Linux
- **Flexible API**: Common interface for different transcription engines

## Parakeet Performance

Using the int8 quantized Parakeet model, performance benchmarks:

- **30x real time** on MBP M4 Max
- **20x real time** on Zen 3 (5700X)
- **5x real time** on Skylake (i5-6500)
- **5x real time** on Jetson Nano CPU


### Required Model Files

**Parakeet Model Directory Structure:**
```
models/parakeet-v0.3/
├── encoder-model.onnx           # Encoder model (FP32)
├── encoder-model.int8.onnx      # Encoder model (For quantized)
├── decoder_joint-model.onnx    # Decoder/joint model (FP32)
├── decoder_joint-model.int8.onnx # Decoder/joint model (For quantized)
├── nemo128.onnx                 # Audio preprocessor
├── vocab.txt                    # Vocabulary file
```

**Whisper Model:**
- Single GGML file (e.g., `whisper-medium-q4_1.bin`)

**Audio Requirements:**
- Format: WAV
- Sample Rate: 16 kHz
- Channels: Mono (1 channel)
- Bit Depth: 16-bit
- Encoding: PCM

## Model Downloads

- **Parakeet**: https://huggingface.co/istupakov/parakeet-tdt-0.6b-v3-onnx/tree/main
- **Whisper**: https://huggingface.co/ggerganov/whisper.cpp/tree/main

## Usage

```rust
use transcribe_rs::{TranscriptionEngine, engines::parakeet::ParakeetEngine};
use std::path::PathBuf;

let mut engine = ParakeetEngine::new();
engine.load_model(&PathBuf::from("path/to/model"))?;
let result = engine.transcribe_file(&PathBuf::from("audio.wav"), None)?;
println!("{}", result.text);
```

## Running the Example

### Setup

1. **Create the models directory:**
   ```bash
   mkdir models
   ```

2. **Download Parakeet Model (recommended for performance):**
   ```bash
   # Download and extract Parakeet model
   cd models
   wget https://blob.handy.computer/parakeet-v3-int8.tar.gz
   tar -xzf parakeet-v3-int8.tar.gz
   rm parakeet-v3-int8.tar.gz
   cd ..
   ```

3. **Or Download Whisper Model (alternative):**
   ```bash
   # Download Whisper model
   cd models
   wget https://blob.handy.computer/whisper-medium-q4_1.bin
   cd ..
   ```

### Running the Example

```bash
cargo run --example transcribe
```

The example will:
- Load the Parakeet model (default) or Whisper model
- Transcribe `samples/dots.wav`
- Display timing information and transcription results
- Show real-time speedup factor

**To switch engines**, edit `examples/transcribe.rs` and change:
```rust
let engine_type = Engine::Parakeet; // or Engine::Whisper
```

## Acknowledgments

- Big thanks to [istupakov](https://github.com/istupakov/onnx-asr) for the excellent ONNX implementation of Parakeet
- Thanks to NVIDIA for releasing the Parakeet model
- Thanks to the [whisper.cpp](https://github.com/ggerganov/whisper.cpp) project for the Whisper implementation
