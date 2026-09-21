use std::path::Path;

pub const DEFAULT_RATIO: f32 = 3.0;
pub const MIN_RATIO: f32 = 1.5;
pub const MAX_RATIO: f32 = 5.0;
pub const SETTING_FILE: &str = "speech_threshold_ratio";

const FRAME_SAMPLES: usize = 512;
const WINDOW_SAMPLES: u64 = 5 * 16_000;
const SILENCE_FLOOR: f32 = 2.5e-4;
const STABLE_ROOM_WINDOW_SAMPLES: u64 = 2 * 16_000;
const STABLE_ROOM_MIN_SAMPLES: u64 = 16_000;
const STABLE_ROOM_MAX_SPREAD: f32 = 2.5;

/// Microphone speech detector relative to the recent room level.
pub struct RoomSpeechDetector {
    enter_ratio: f32,
    leave_ratio: f32,
    frame: Vec<f32>,
    room: Vec<(u64, f32)>,
    all: Vec<(u64, f32)>,
    room_level: Option<f32>,
    in_speech: bool,
    position: u64,
}

impl RoomSpeechDetector {
    pub fn new(enter_ratio: f32) -> Self {
        let enter_ratio = valid_ratio(enter_ratio).unwrap_or(DEFAULT_RATIO);
        Self {
            enter_ratio,
            leave_ratio: (enter_ratio * 2.0 / 3.0).max(1.05),
            frame: Vec::new(),
            room: Vec::new(),
            all: Vec::new(),
            room_level: None,
            in_speech: false,
            position: 0,
        }
    }

    pub fn push(&mut self, samples: &[f32]) -> bool {
        self.frame.extend_from_slice(samples);
        while self.frame.len() >= FRAME_SAMPLES {
            let level = rms(&self.frame[..FRAME_SAMPLES]);
            self.frame.drain(..FRAME_SAMPLES);
            self.position += FRAME_SAMPLES as u64;

            if let Some(room) = self.room_level {
                let ratio = if self.in_speech {
                    self.leave_ratio
                } else {
                    self.enter_ratio
                };
                self.in_speech = level > room.max(SILENCE_FLOOR) * ratio;
            }

            if level >= SILENCE_FLOOR {
                self.all.push((self.position, level));
                if !self.in_speech {
                    self.room.push((self.position, level));
                }
            }
            let oldest = self.position.saturating_sub(WINDOW_SAMPLES);
            self.room.retain(|&(at, _)| at >= oldest);
            self.all.retain(|&(at, _)| at >= oldest);

            if let Some(level) = percentile(&self.room, 0.10) {
                self.room_level = Some(level);
            }
            // A stable louder room can correct an estimate that started too low.
            // Speech varies too much to pass this narrow spread test in normal use.
            let stable_from = self.position.saturating_sub(STABLE_ROOM_WINDOW_SAMPLES);
            let stable_start = self.all.partition_point(|&(at, _)| at < stable_from);
            let stable = &self.all[stable_start..];
            let stable_span = stable
                .first()
                .zip(stable.last())
                .map(|(&(first, _), &(last, _))| last.saturating_sub(first))
                .unwrap_or(0);
            if stable_span >= STABLE_ROOM_MIN_SAMPLES {
                let low = percentile(stable, 0.10).unwrap();
                let high = percentile(stable, 1.0).unwrap();
                if high <= low * STABLE_ROOM_MAX_SPREAD {
                    self.room_level = Some(self.room_level.unwrap_or(low).max(low));
                }
            }
        }
        self.in_speech
    }
}

pub fn read_ratio(files_dir: Option<&Path>) -> f32 {
    files_dir
        .and_then(|dir| std::fs::read_to_string(dir.join(SETTING_FILE)).ok())
        .as_deref()
        .and_then(parse_ratio)
        .unwrap_or(DEFAULT_RATIO)
}

pub fn parse_ratio(value: &str) -> Option<f32> {
    valid_ratio(value.trim().parse().ok()?)
}

fn valid_ratio(value: f32) -> Option<f32> {
    (value.is_finite() && (MIN_RATIO..=MAX_RATIO).contains(&value)).then_some(value)
}

fn percentile(values: &[(u64, f32)], fraction: f32) -> Option<f32> {
    if values.is_empty() {
        return None;
    }
    let mut sorted: Vec<f32> = values.iter().map(|&(_, value)| value).collect();
    sorted.sort_by(|a, b| a.total_cmp(b));
    Some(sorted[((sorted.len() - 1) as f32 * fraction).round() as usize])
}

fn rms(samples: &[f32]) -> f32 {
    (samples.iter().map(|x| x * x).sum::<f32>() / samples.len().max(1) as f32).sqrt()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn frames(level: f32, count: usize) -> Vec<f32> {
        vec![level; FRAME_SAMPLES * count]
    }

    fn seeded(ratio: f32, room: f32) -> RoomSpeechDetector {
        let mut detector = RoomSpeechDetector::new(ratio);
        assert!(!detector.push(&frames(room, 20)));
        detector
    }

    #[test]
    fn decisions_follow_room_ratio_across_gain() {
        for gain in [0.25, 1.0, 4.0] {
            let room = 0.005 * gain;
            let mut detector = seeded(DEFAULT_RATIO, room);
            assert!(detector.push(&frames(room * 4.0, 4)));
            assert!(!detector.push(&frames(room, 4)));
        }
    }

    #[test]
    fn setting_changes_a_threshold_adjacent_decision() {
        let mut sensitive = seeded(2.0, 0.01);
        let mut selective = seeded(4.0, 0.01);
        assert!(sensitive.push(&frames(0.025, 2)));
        assert!(!selective.push(&frames(0.025, 2)));
    }

    #[test]
    fn stable_louder_room_recovers_then_detects_speech() {
        let mut detector = seeded(DEFAULT_RATIO, 0.002);
        detector.push(&frames(0.02, 170));
        assert!(!detector.push(&frames(0.02, 2)));
        assert!(detector.push(&frames(0.08, 2)));
    }

    #[test]
    fn invalid_settings_use_the_effective_default() {
        for value in ["", "NaN", "1.49", "5.01", "broken"] {
            assert_eq!(parse_ratio(value), None);
        }
        assert_eq!(parse_ratio(" 3.5\n"), Some(3.5));
        assert_eq!(read_ratio(None), DEFAULT_RATIO);
    }
}
