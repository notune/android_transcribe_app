//! Small audio helpers shared between the engine and the subtitle pipeline.

const SAMPLE_RATE: usize = 16_000;
const INFERENCE_FRAME_SAMPLES: usize = 320; // 20 ms
const INFERENCE_CONTEXT_SAMPLES: usize = SAMPLE_RATE; // 1 s
const INFERENCE_QUIET_RATIO: f32 = 1.5;
const INFERENCE_SILENCE_FLOOR: f32 = 2.5e-4;

/// Centre of the quietest 100 ms window in `samples[from..to]`; used to pick a
/// natural split point when audio must be cut mid-speech.
pub fn find_quietest_split(samples: &[f32], from: usize, to: usize) -> usize {
    const WIN: usize = 1_600; // 100 ms
    if from + WIN > to {
        return to;
    }
    let mut best_pos = to;
    let mut best_energy = f32::MAX;
    let mut i = from;
    while i + WIN <= to {
        let energy: f32 = samples[i..i + WIN].iter().map(|&x| x * x).sum();
        if energy < best_energy {
            best_energy = energy;
            best_pos = i + WIN / 2;
        }
        i += WIN / 2;
    }
    best_pos
}

fn rms(block: &[f32]) -> f32 {
    (block.iter().map(|&x| x * x).sum::<f32>() / block.len().max(1) as f32).sqrt()
}

/// Removes sustained clear quiet from the copy sent to the recognizer.
///
/// Short pauses and low-energy words keep one second of their real recorded
/// context on each side. The caller retains the original recording.
pub fn compact_for_inference(samples: &[f32]) -> Vec<f32> {
    if samples.is_empty() {
        return Vec::new();
    }

    let levels: Vec<f32> = samples.chunks(INFERENCE_FRAME_SAMPLES).map(rms).collect();
    let mut sorted = levels.clone();
    sorted.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
    let room = sorted[((sorted.len() - 1) as f32 * 0.10).round() as usize];
    let quiet = room.max(INFERENCE_SILENCE_FLOOR) * INFERENCE_QUIET_RATIO;

    let active: Vec<usize> = levels
        .iter()
        .enumerate()
        .filter_map(|(frame, &level)| (level > quiet).then_some(frame))
        .collect();
    if active.is_empty() {
        // A steady level is not proof of silence: the percentile can be speech.
        return if levels.iter().all(|&level| level <= INFERENCE_SILENCE_FLOOR) {
            Vec::new()
        } else {
            samples.to_vec()
        };
    }

    let mut ranges = Vec::<(usize, usize)>::new();
    for frame in active {
        let sound_start = frame * INFERENCE_FRAME_SAMPLES;
        let sound_end = ((frame + 1) * INFERENCE_FRAME_SAMPLES).min(samples.len());
        let start = sound_start.saturating_sub(INFERENCE_CONTEXT_SAMPLES);
        let end = (sound_end + INFERENCE_CONTEXT_SAMPLES).min(samples.len());
        match ranges.last_mut() {
            Some((_, prior_end)) if start <= *prior_end => *prior_end = (*prior_end).max(end),
            _ => ranges.push((start, end)),
        }
    }

    let mut compacted = Vec::with_capacity(ranges.iter().map(|(start, end)| end - start).sum());
    for (start, end) in ranges {
        compacted.extend_from_slice(&samples[start..end]);
    }
    compacted
}

#[cfg(test)]
mod tests {
    use super::*;

    fn quiet(seconds: f32) -> Vec<f32> {
        vec![0.0; (seconds * SAMPLE_RATE as f32) as usize]
    }

    fn word(seconds: f32, level: f32) -> Vec<f32> {
        (0..(seconds * SAMPLE_RATE as f32) as usize)
            .map(|i| if i % 2 == 0 { level } else { -level })
            .collect()
    }

    #[test]
    fn compaction_removes_only_sustained_clear_quiet() {
        let first = word(0.25, 0.1);
        let second = word(0.25, 0.1);
        let mut audio = quiet(3.0);
        audio.extend_from_slice(&first);
        audio.extend(quiet(4.0));
        audio.extend_from_slice(&second);
        audio.extend(quiet(3.0));

        let compacted = compact_for_inference(&audio);
        assert!(compacted.len() < 5 * SAMPLE_RATE);
        assert!(compacted.windows(first.len()).any(|window| window == first));
        assert!(compacted
            .windows(second.len())
            .any(|window| window == second));
    }

    #[test]
    fn compaction_keeps_an_immediate_stop_single_word() {
        let word = word(0.28, 0.1);
        let mut audio = quiet(2.0);
        audio.extend_from_slice(&word);
        audio.extend(quiet(0.08));

        let compacted = compact_for_inference(&audio);
        assert!(compacted.windows(word.len()).any(|window| window == word));
        assert!(compacted.len() <= word.len() + INFERENCE_CONTEXT_SAMPLES * 2);
    }

    #[test]
    fn compaction_preserves_quiet_speech() {
        let word = word(0.35, 0.0008);
        let mut audio = quiet(2.0);
        audio.extend_from_slice(&word);
        audio.extend(quiet(2.0));

        let compacted = compact_for_inference(&audio);
        assert!(compacted.windows(word.len()).any(|window| window == word));
    }

    #[test]
    fn steady_nonquiet_audio_without_leading_silence_is_retained() {
        for level in [0.1, 0.0008] {
            let audio = word(0.28, level);
            assert_eq!(compact_for_inference(&audio), audio);
        }
    }

    #[test]
    fn uniformly_quiet_audio_skips_inference() {
        assert!(compact_for_inference(&quiet(70.0)).is_empty());
    }
}
