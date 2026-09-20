//! Cutting a live microphone stream into transcribable pieces.
//!
//! A [`Segmenter`] turns the small blocks that arrive from the microphone
//! (~64 ms each) into *pieces* of audio that can be transcribed while the user
//! is still talking, so that the wait after they stop is one piece instead of
//! the whole utterance.
//!
//! It is pure: no JNI, no threads, no engine, no Android. It decides only
//! *where the cuts are*; who runs the model and in what order is the caller's
//! business. That is what lets the whole of it run under `cargo test` against
//! synthetic audio on any machine.
//!
//! The state machine is the one `subtitle.rs` has proven in production for
//! live captions, extracted rather than invented: pre-roll so the first word is
//! not clipped, a cut on trailing silence, and a length cap with a quiet-point
//! split that carries the remainder forward. The test module holds that
//! original state machine as an oracle and requires
//! [`SegmenterConfig::subtitles`] to agree with it sample for sample.
//!
//! Dictation then differs from captions in three ways, all of them in the
//! config and none of them in the code:
//!
//! - **Nothing is ever cut inside speech.** Captions cut at the quietest point
//!   whenever the 6 s cap is hit, because a caption that waits is useless.
//!   Dictation has no cap at all: a piece ends at a real pause or not at all.
//!   Speak for four minutes without pausing and it is one piece, preserving the
//!   current app's uncapped whole-utterance behavior.
//! - **The room, not a fixed number, decides what silence is.** Captions tap
//!   device audio, where silence is digital silence. A microphone always has a
//!   room behind it. See [`SpeechGauge`].
//! - **Captured words are not dropped and nothing is fabricated.** Captions over a live
//!   broadcast can never catch up, so `subtitle.rs` discards a fragment too
//!   short to hold a word. Dictation always catches up, because the audio ends
//!   when the speaker says so, and the speaker's words are not recoverable: a
//!   piece with too little speech in it is held and joined to the front of the
//!   next one instead. See [`ShortPiece`] and [`PieceFloor`].

use crate::audio::find_quietest_split;

pub const SAMPLE_RATE: usize = 16_000;

/// Defaults and limits for the pause that ends a dictation piece. This starts
/// inference; [`Piece::pause_before`] separately tells the consumer whether the
/// measured pause ended a sentence.
pub const DEFAULT_SPLIT_SECONDS: f32 = 3.0;
pub const MIN_SPLIT_SECONDS: f32 = 1.5;
pub const MAX_SPLIT_SECONDS: f32 = 8.0;

/// Prevents a changing room from trapping the adaptive estimate below its
/// current background. This floors the speech-excluding estimate; it does not
/// replace it, so continuous speech cannot become the room by itself.
const ALL_FRAME_FLOOR_PERCENTILE: f32 = 0.10;

/// Why a piece was cut where it was. Carried on the piece so a caller, a log
/// line or a test can tell a clean boundary from a forced one.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CutCause {
    /// Trailing silence reached the split threshold. The only cause dictation
    /// can produce mid-session.
    Silence,
    /// A length cap was reached: cut at the quietest point, which may be
    /// inside speech. Captions only — dictation configures no cap.
    Cap,
    /// The session ended and this is whatever was still open.
    Flush,
}

/// One finished piece of audio, ready to transcribe.
#[derive(Clone, Debug)]
pub struct Piece {
    pub samples: Vec<f32>,
    /// Offset of `samples[0]` within everything pushed this session. Lets a
    /// test or a measurement say exactly where a boundary fell.
    pub start: u64,
    pub cause: CutCause,
    /// The whole silence between the last speech of the previous piece and the
    /// first speech of this one, in seconds; `0.0` for the first piece of a
    /// session and after a cut that was not a pause.
    ///
    /// It is the *whole* gap, not the split threshold that ended the previous
    /// piece. Those are different numbers and only the first one is useful: a
    /// piece is cut as soon as the silence reaches the split threshold, so if
    /// the report stopped there every pause would arrive as ~1.5 s and a
    /// consumer could never tell a hesitation from the end of a sentence. The
    /// figure therefore keeps growing until the speaker speaks again.
    ///
    /// This is the detected silence, measured from the audio. It is not the
    /// gap between two deliveries: that one is polluted by however long the
    /// model took, which is why the consumer cannot work it out for itself.
    pub pause_before: f32,
}

impl Piece {
    /// Offset one past this piece's last sample, in the pushed stream.
    pub fn end(&self) -> u64 {
        self.start + self.samples.len() as u64
    }
}

/// How a block of audio is judged to carry speech or not.
///
/// This is the one thing dictation could not inherit from `subtitle.rs`.
/// Captions tap *device audio*, where a silent stretch really is digital
/// silence, so a fixed 0.004 RMS threshold is enough. A microphone always has
/// a room behind it, and the room is where the fixed threshold fails: the
/// app's own bundled `bench.wav` has a room tone above that threshold, so the
/// fixed gauge finds no quiet stretch. With no length cap, a gauge
/// that finds no pause produces one piece for the whole recording, and the
/// feature does nothing at all — silently.
#[derive(Clone, Copy, Debug)]
pub enum SpeechGauge {
    /// A block is sound when its RMS reaches `rms`. What `subtitle.rs` does.
    Absolute { rms: f32 },
    /// A block is sound when it stands clear of the room it was recorded in.
    ///
    /// The room level is a low percentile of the recent blocks that were *not*
    /// judged speech:
    ///
    /// - *Percentile*, not minimum: one freak block — a lead-in of digital
    ///   silence, a click — must not set the room level for a whole session.
    ///   A minimum-statistics estimator did exactly that on `bench.wav`, whose
    ///   first block is near-digital silence, and then found no pause at all.
    /// - *Not judged speech*: speech must never replace the room estimate. A
    ///   separate low percentile over all recent frames may only floor that
    ///   estimate, which lets it follow a louder room without absorbing the
    ///   voice itself.
    /// - *Recent*: the room is what it is now, not what it was at the start.
    ///
    /// The judgement is made on fixed `frame_samples` frames, not on the
    /// blocks the microphone happens to deliver. The driver chooses that block
    /// size and it varies between devices and audio paths; a short block gives
    /// a noisier level estimate than a long one, which moved the estimated room
    /// level and with it the boundaries. A fixed frame makes the answer the
    /// same whether the blocks arrive 8 ms or 256 ms at a time.
    ///
    /// Until an estimate exists nothing is speech, so the first frames of a
    /// session go into the estimate rather than into a decision they have no
    /// basis for.
    ///
    /// The margin is a ratio, not a difference, because background levels
    /// differ by a factor of hundreds between a bedroom and a car while the
    /// ratio of speech to background stays roughly constant. Hysteresis — a
    /// higher ratio to enter speech than to leave it — stops a level sitting
    /// on the line from chattering.
    ///
    /// Where it gives up, and why that is the safe direction: a sound that is
    /// both steady and at speaking level for longer than `window_samples` can
    /// be taken for background. Real continuous-speech replay guards this edge.
    Room {
        /// Length of one analysis frame. Levels are measured over this, not
        /// over the delivered block.
        frame_samples: usize,
        /// Percentile of the room window taken as the room level, `0.0..=1.0`.
        percentile: f32,
        /// How much recent audio the estimate is built from. A *time* window,
        /// not a count of retained frames, so a stretch of speech ages the
        /// estimate out instead of preserving it.
        window_samples: usize,
        /// A frame enters speech above this multiple of the room level, and
        /// leaves it below `leave_ratio`.
        enter_ratio: f32,
        leave_ratio: f32,
        /// Below this a frame carries no signal at all: a muted or zero-filled
        /// buffer, not a room. Such frames are kept out of the estimate, and
        /// the estimate itself never goes below it.
        ///
        /// This one line is what stops the estimator getting stuck. Filtering
        /// speech out of the estimate is what keeps speech from being absorbed
        /// into the room, but it makes the estimator one-way: too *high* is
        /// self-correcting, because everything then counts as room, everything
        /// is admitted, and the percentile falls within one window — while too
        /// *low* is a trap, because everything then counts as speech, nothing
        /// is admitted, and nothing ever corrects it. The only thing that puts
        /// the estimate implausibly low is a dead lead-in: Android hands out
        /// zeroed buffers while the capture stream starts, and `bench.wav`
        /// opens with 0.32 s at 0.00016 RMS. Admitting those pinned the
        /// estimate near zero, every later frame then stood far above it, and
        /// the gauge called whole clips speech. Keeping dead
        /// audio out is enough, and nothing else needs a rescue: a rule that
        /// fed speech back into the estimate to escape the trap absorbed a
        /// steady tone within seconds and cut it in half, which is the one
        /// thing that must never happen.
        ///
        /// 2.5e-4 RMS is about -72 dBFS. A microphone with any gain on it, in
        /// any room, produces more than that.
        silence_floor: f32,
    },
}

/// What becomes of a piece with too little speech in it to transcribe well.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ShortPiece {
    /// Discard it. Captions: a fragment is noise, and holding it would delay
    /// the caption after it.
    Drop,
    /// Hold it and put it at the front of the next piece. Dictation: the
    /// fragment may be a word, and captured speech is not recoverable.
    Carry,
}

/// The least speech a piece must hold to be worth transcribing on its own.
#[derive(Clone, Copy, Debug)]
pub struct PieceFloor {
    pub samples: usize,
    /// Measure the speech in the piece rather than its whole length. Dictation
    /// does: a piece is mostly the quiet margins kept around the words, so its
    /// length says little about how much the model has to work with.
    pub speech_only: bool,
    pub short: ShortPiece,
}

/// How an over-long piece is cut when there is no pause to cut at. Captions
/// need this because a caption that waits is useless; dictation sets `None`.
#[derive(Clone, Copy, Debug)]
pub struct CapPolicy {
    /// Length at which the piece is cut regardless.
    pub samples: usize,
    /// How far back from the end to look for the quietest point.
    pub search_samples: usize,
}

/// Every threshold the segmenter uses. There are two of these and no third:
/// [`SegmenterConfig::dictation`] is what ships, and
/// [`SegmenterConfig::subtitles`] exists so the tests can prove the extraction
/// reproduces `subtitle.rs` exactly.
#[derive(Clone, Copy, Debug)]
pub struct SegmenterConfig {
    /// How a block is judged to carry speech.
    pub gauge: SpeechGauge,
    /// Audio kept ahead of speech and prepended once speech starts, so the
    /// first word is not clipped.
    pub preroll_samples: usize,
    /// Silence kept after the last speech when cutting on silence.
    pub final_tail_samples: usize,
    /// The least speech a piece must hold to be transcribed on its own.
    pub floor: PieceFloor,
    /// Trailing silence that ends a piece.
    pub finalize_silence_samples: usize,
    /// How a piece is cut when it gets long with no pause in it. `None` means
    /// it never is.
    pub cap: Option<CapPolicy>,
    /// Nonzero enables lossless interval retention for dictation. The numeric
    /// value remains for source compatibility; it no longer caps capture.
    /// Zero keeps the caption behaviour.
    pub quiet_retain_samples: usize,
}

impl SegmenterConfig {
    /// Dictation with the default split pause.
    pub fn dictation() -> Self {
        Self::dictation_with_split_seconds(DEFAULT_SPLIT_SECONDS)
    }

    /// Dictation: no length cap, a room-relative gauge, and a piece that ends
    /// after the selected trailing-silence duration or not at all. Invalid
    /// values use [`DEFAULT_SPLIT_SECONDS`].
    pub fn dictation_with_split_seconds(split_seconds: f32) -> Self {
        let split_seconds = if split_seconds.is_finite()
            && (MIN_SPLIT_SECONDS..=MAX_SPLIT_SECONDS).contains(&split_seconds)
        {
            split_seconds
        } else {
            DEFAULT_SPLIT_SECONDS
        };
        SegmenterConfig {
            gauge: SpeechGauge::Room {
                frame_samples: 512, // 32 ms
                percentile: 0.10,
                window_samples: 5 * SAMPLE_RATE,
                enter_ratio: 3.0,
                leave_ratio: 2.0,
                silence_floor: 2.5e-4,
            },
            // Captions keep 0.4 s before a piece and 0.2 s after it. Dictation
            // keeps half a second of the real room on both sides because the
            // model can damage a word that sits too close to a cut. The split
            // threshold guarantees at least 1.5 s at a cut, so 0.5 s on each
            // side always exists and the two margins cannot meet.
            preroll_samples: 8_000,    // 0.5 s
            final_tail_samples: 8_000, // 0.5 s
            // A piece with under 1.5 s of speech is not transcribed on its own.
            // It is held and merged into the next piece instead of dropped.
            // `flush` ignores this: a short last piece is what tapping
            // stop after a short utterance produces, and it is correct.
            floor: PieceFloor {
                samples: (1.5 * SAMPLE_RATE as f32) as usize,
                speech_only: true,
                short: ShortPiece::Carry,
            },
            finalize_silence_samples: (split_seconds * SAMPLE_RATE as f32) as usize,
            cap: None,
            quiet_retain_samples: 60 * SAMPLE_RATE,
        }
    }

    /// The live-caption thresholds exactly as `subtitle.rs` uses them. Present
    /// so the characterization tests can drive this code and the original side
    /// by side; `subtitle.rs` itself is deliberately left alone, because live
    /// captions are the one surface no test on this route can reach.
    pub fn subtitles() -> Self {
        SegmenterConfig {
            gauge: SpeechGauge::Absolute { rms: 0.004 },
            preroll_samples: 6_400,    // 0.4 s
            final_tail_samples: 3_200, // 0.2 s
            floor: PieceFloor {
                samples: 4_000, // 0.25 s
                speech_only: false,
                short: ShortPiece::Drop,
            },
            finalize_silence_samples: 11_200, // 0.7 s
            cap: Some(CapPolicy {
                samples: 6 * SAMPLE_RATE,
                search_samples: 3 * SAMPLE_RATE,
            }),
            quiet_retain_samples: 0,
        }
    }
}

/// Cuts a stream of microphone blocks into [`Piece`]s.
///
/// Feed it with [`Segmenter::push`] and end the session with
/// [`Segmenter::flush`]. In dictation mode, every captured sample comes back
/// out in exactly one piece, in order.
pub struct Segmenter {
    cfg: SegmenterConfig,
    /// The piece being accumulated.
    segment: Vec<f32>,
    /// Silence at the very end of `segment`, in samples. Exactly the
    /// `silence_run` counter `subtitle.rs` keeps.
    silence_run: usize,
    /// Pre-speech audio, waiting to be prepended when speech starts.
    preroll: Vec<f32>,
    has_speech: bool,
    emitted_any: bool,
    /// Speech in `segment`, in samples: what [`PieceFloor`] measures when it is
    /// `speech_only`.
    speech_samples: usize,
    /// A piece held back because it had too little speech, waiting at the front
    /// of the next one. See [`ShortPiece::Carry`].
    carry: Option<(Vec<f32>, u64, usize)>,
    /// Room estimate state, for [`SpeechGauge::Room`]: samples not yet making
    /// up a whole analysis frame, the admitted frames as `(stream position,
    /// level)`, the level derived from them, the hysteresis state and the position
    /// the gauge has consumed to. Per session, not per piece: the room does
    /// not change between two sentences.
    frame: Vec<f32>,
    room: Vec<(u64, f32)>,
    /// Every non-dead frame in the same time window. Its low percentile only
    /// floors `room_level`, breaking the one-way trap when louder background
    /// was first classified as speech and therefore excluded from `room`.
    all_frames: Vec<(u64, f32)>,
    room_level: Option<f32>,
    in_speech: bool,
    gauge_pos: u64,
    /// Offset of `segment[0]` in the pushed stream.
    segment_origin: u64,
    /// Offset of `preroll[0]`, or of the next sample when `preroll` is empty.
    preroll_origin: u64,
    total_pushed: u64,
    /// Silence since the last emitted piece's speech ended, in seconds,
    /// waiting to be reported on the next piece. Still growing while the
    /// speaker stays quiet.
    pending_pause: f32,
}

impl Segmenter {
    pub fn new(cfg: SegmenterConfig) -> Self {
        Segmenter {
            cfg,
            segment: Vec::new(),
            silence_run: 0,
            preroll: Vec::new(),
            has_speech: false,
            emitted_any: false,
            speech_samples: 0,
            carry: None,
            frame: Vec::new(),
            room: Vec::new(),
            all_frames: Vec::new(),
            room_level: None,
            in_speech: false,
            gauge_pos: 0,
            segment_origin: 0,
            preroll_origin: 0,
            total_pushed: 0,
            pending_pause: 0.0,
        }
    }

    /// Clears everything for a new session, keeping the configuration.
    pub fn reset(&mut self) {
        let cfg = self.cfg;
        *self = Segmenter::new(cfg);
    }

    /// Samples pushed since the last reset.
    pub fn total_pushed(&self) -> u64 {
        self.total_pushed
    }

    /// Feeds one block of 16 kHz mono samples. Returns a finished piece when
    /// this block completed one.
    ///
    /// Blocks are expected to be short (the microphone delivers ~64 ms).
    pub fn push(&mut self, block: &[f32]) -> Option<Piece> {
        if block.is_empty() {
            return None;
        }
        let sound = self.is_sound(block);
        self.total_pushed += block.len() as u64;

        if !self.has_speech {
            if !sound {
                if self.emitted_any {
                    // The gap goes on. Keep measuring it: the consumer decides
                    // whether this was a hesitation or the end of a sentence,
                    // and it can only do that from the whole pause.
                    self.pending_pause += block.len() as f32 / SAMPLE_RATE as f32;
                }
                self.preroll.extend_from_slice(block);
                if !self.retains_all_audio() {
                    let excess = self.preroll.len().saturating_sub(self.cfg.preroll_samples);
                    if excess > 0 {
                        self.preroll.drain(..excess);
                        self.preroll_origin += excess as u64;
                    }
                }
                return None;
            }
            // Speech begins. Seed the piece with the tail of the pre-roll so
            // the first word is not clipped, and drop the rest, which is
            // silence nobody needs transcribed.
            //
            // Until something has been emitted, keep as much as the gauge
            // could have needed to settle. A room-relative gauge has no room
            // to compare against at the start of a session, so it can call the
            // first words room and leave them sitting in the pre-roll; taking
            // only the usual margin would throw them away. The bound is the
            // gauge's own window, because that is how long it can take to
            // settle — not the whole retained buffer, which would put a minute
            // of pre-speech waiting through the model for
            // nothing. A fixed-threshold gauge needs no settling and keeps the
            // caption behaviour exactly.
            let take = if self.retains_all_audio() {
                self.preroll.len()
            } else if self.emitted_any {
                self.preroll.len().min(self.cfg.preroll_samples)
            } else {
                self.preroll
                    .len()
                    .min(self.cfg.preroll_samples.max(self.settle_samples()))
            };
            let drop_n = self.preroll.len() - take;
            self.segment_origin = self.preroll_origin + drop_n as u64;
            self.segment.clear();
            self.segment.extend_from_slice(&self.preroll[drop_n..]);
            self.preroll.clear();
            self.preroll_origin = self.total_pushed;
            self.segment.extend_from_slice(block);
            self.silence_run = 0;
            self.speech_samples = block.len();
            self.has_speech = true;
        } else {
            self.segment.extend_from_slice(block);
            if sound {
                self.silence_run = 0;
                self.speech_samples += block.len();
            } else {
                self.silence_run += block.len();
            }
        }

        self.try_cut()
    }

    /// Ends the session and returns whatever is still open. Captions trim their
    /// tail as before; dictation preserves the complete captured interval.
    ///
    /// When nothing was ever emitted — a whole session that stayed under the
    /// gauge, someone speaking very quietly or far from the microphone — the
    /// retained audio is returned instead, so the session still produces text
    /// rather than silently nothing.
    /// A piece held back for being too short is released here whatever its
    /// length: tapping stop straight after a short utterance is the one case
    /// where a short piece is both unavoidable and right.
    pub fn flush(&mut self) -> Option<Piece> {
        let open = if self.has_speech && !self.segment.is_empty() {
            let mut samples = std::mem::take(&mut self.segment);
            if !self.retains_all_audio() {
                let len = samples.len();
                let keep = (len - self.silence_run + self.cfg.final_tail_samples).min(len);
                samples.truncate(keep);
            }
            Some((samples, self.segment_origin))
        } else if !self.preroll.is_empty() && (self.retains_all_audio() || !self.emitted_any) {
            Some((std::mem::take(&mut self.preroll), self.preroll_origin))
        } else {
            None
        };
        let open = match (self.carry.take(), open) {
            (Some((mut held, at, _)), Some((samples, _))) => {
                held.extend_from_slice(&samples);
                Some((held, at))
            }
            (Some((held, at, _)), None) => Some((held, at)),
            (None, open) => open,
        };
        let pause_before = self.pending_pause;

        // Leave the segmenter ready for the next session, but remember what
        // this one pushed: the caller uses it to tell "no audio at all" from
        // "audio that held no speech".
        let total = self.total_pushed;
        self.reset();
        self.total_pushed = total;

        match open {
            Some((samples, start)) if !samples.is_empty() => Some(Piece {
                samples,
                start,
                cause: CutCause::Flush,
                pause_before,
            }),
            _ => None,
        }
    }

    // --- internals ---

    /// Whether this block carries speech, and, for the room gauge, the one
    /// place the room estimate is updated.
    fn is_sound(&mut self, block: &[f32]) -> bool {
        match self.cfg.gauge {
            SpeechGauge::Absolute { rms: threshold } => rms(block) >= threshold,
            SpeechGauge::Room {
                frame_samples,
                percentile,
                window_samples,
                enter_ratio,
                leave_ratio,
                silence_floor,
            } => {
                self.frame.extend_from_slice(block);
                while self.frame.len() >= frame_samples {
                    let level = rms(&self.frame[..frame_samples]);
                    self.frame.drain(..frame_samples);
                    self.gauge_pos += frame_samples as u64;

                    // With no estimate yet, nothing is speech: the first frames
                    // of a session go into the estimate rather than into a
                    // decision they have no basis for.
                    if let Some(room) = self.room_level {
                        let room = room.max(silence_floor);
                        let ratio = if self.in_speech {
                            leave_ratio
                        } else {
                            enter_ratio
                        };
                        self.in_speech = level > room * ratio;
                    }

                    if !self.in_speech && level >= silence_floor {
                        self.room.push((self.gauge_pos, level));
                    }
                    if level >= silence_floor {
                        self.all_frames.push((self.gauge_pos, level));
                    }
                    let oldest = self.gauge_pos.saturating_sub(window_samples as u64);
                    while self.room.first().is_some_and(|&(at, _)| at < oldest) {
                        self.room.remove(0);
                    }
                    while self.all_frames.first().is_some_and(|&(at, _)| at < oldest) {
                        self.all_frames.remove(0);
                    }
                    if !self.room.is_empty() {
                        let mut sorted: Vec<f32> = self.room.iter().map(|&(_, l)| l).collect();
                        sorted
                            .sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
                        let at = ((sorted.len() - 1) as f32 * percentile).round() as usize;
                        self.room_level = Some(sorted[at]);
                    }
                    if !self.all_frames.is_empty() {
                        let mut sorted: Vec<f32> =
                            self.all_frames.iter().map(|&(_, level)| level).collect();
                        sorted
                            .sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
                        let at = ((sorted.len() - 1) as f32 * ALL_FRAME_FLOOR_PERCENTILE).round()
                            as usize;
                        let floor = sorted[at];
                        self.room_level = Some(self.room_level.unwrap_or(floor).max(floor));
                    }
                }
                self.in_speech
            }
        }
    }

    /// How long the gauge can take to work out what the room is.
    fn settle_samples(&self) -> usize {
        match self.cfg.gauge {
            SpeechGauge::Absolute { .. } => 0,
            SpeechGauge::Room { window_samples, .. } => window_samples,
        }
    }

    fn retains_all_audio(&self) -> bool {
        self.cfg.quiet_retain_samples > 0 && self.cfg.floor.short == ShortPiece::Carry
    }

    fn try_cut(&mut self) -> Option<Piece> {
        let len = self.segment.len();

        if self.silence_run >= self.cfg.finalize_silence_samples {
            // Captions trim for latency. Dictation keeps the full captured
            // interval: the detector schedules inference, but never deletes
            // samples it classified as silence.
            let keep = if self.retains_all_audio() {
                len
            } else {
                (len - self.silence_run + self.cfg.final_tail_samples).min(len)
            };
            let pause = self.silence_run as f32 / SAMPLE_RATE as f32;
            return self.cut_at_silence(keep, pause);
        }

        if let Some(cap) = self.cfg.cap {
            if len >= cap.samples {
                // No pause to use. Cut at the quietest point in the search
                // window and carry the remainder forward, so a word is split
                // across two pieces rather than lost.
                let from = len.saturating_sub(cap.search_samples);
                let at = find_quietest_split(&self.segment, from, len);
                return self.cut_at_quietest(at);
            }
        }

        None
    }

    fn cut_at_silence(&mut self, keep: usize, pause: f32) -> Option<Piece> {
        let mut samples = std::mem::take(&mut self.segment);
        let start = self.segment_origin;
        samples.truncate(keep);
        let speech = self.speech_samples;
        self.silence_run = 0;
        self.speech_samples = 0;
        self.preroll.clear();
        self.has_speech = false;
        self.preroll_origin = self.total_pushed;
        self.segment_origin = self.total_pushed;
        self.finish(samples, start, speech, CutCause::Silence, pause)
    }

    fn cut_at_quietest(&mut self, at: usize) -> Option<Piece> {
        let at = at.min(self.segment.len());
        let mut samples = std::mem::take(&mut self.segment);
        self.segment = samples.split_off(at);
        let start = self.segment_origin;
        self.segment_origin = start + at as u64;
        // The remainder's own silence history is forgotten, exactly as
        // `subtitle.rs` resets `silence_run` after a forced cut.
        self.silence_run = 0;
        let speech = self.speech_samples;
        self.speech_samples = 0;
        self.finish(samples, start, speech, CutCause::Cap, 0.0)
    }

    fn finish(
        &mut self,
        mut samples: Vec<f32>,
        mut start: u64,
        speech: usize,
        cause: CutCause,
        pause_after: f32,
    ) -> Option<Piece> {
        // Anything held back from an earlier cut goes at the front, so the
        // words arrive in the order they were spoken. Only real recorded audio
        // is joined: nothing is padded and no silence is synthesised, because
        // a constant signal is outside the model's recorded-audio distribution.
        // The margins kept on both sides of each cut
        // supply about a second of the real room between the two.
        let mut speech = speech;
        if let Some((mut held, at, held_speech)) = self.carry.take() {
            held.extend_from_slice(&samples);
            samples = held;
            start = at;
            speech += held_speech;
        }
        let measured = if self.cfg.floor.speech_only {
            speech
        } else {
            samples.len()
        };
        if measured < self.cfg.floor.samples {
            // Too little speech for the model to get the words right.
            match self.cfg.floor.short {
                // The gap after this fragment ends up inside the merged piece,
                // so it is not a pause between pieces any more; the pause the
                // next piece reports is still the one before the fragment.
                ShortPiece::Carry => self.carry = Some((samples, start, speech)),
                // Nothing is emitted and nothing is kept, so the gap runs on
                // into the pause the next piece will report.
                ShortPiece::Drop => self.pending_pause += pause_after,
            }
            return None;
        }
        self.emitted_any = true;
        let pause_before = std::mem::replace(&mut self.pending_pause, pause_after);
        Some(Piece {
            samples,
            start,
            cause,
            pause_before,
        })
    }
}

fn rms(block: &[f32]) -> f32 {
    (block.iter().map(|&x| x * x).sum::<f32>() / block.len().max(1) as f32).sqrt()
}

#[cfg(test)]
mod tests {
    use super::*;

    // --- the oracle -------------------------------------------------------
    //
    // A literal transcription of the segmentation state machine in
    // `src/subtitle.rs` as it stands at v0.1.18 (`pushAudio`, from the
    // `has_speech` branch down to the final job). Only the parts that decide
    // *where a cut falls* are kept: partial-hypothesis scheduling, lag
    // dropping and job merging never move a cut.
    //
    // This is what "behaviour-preserving extraction" is checked against. If
    // `Segmenter` under `SegmenterConfig::subtitles()` ever disagrees with it,
    // the extraction changed behaviour.

    const O_SPEECH_RMS: f32 = 0.004;
    const O_PREROLL: usize = 6_400;
    const O_FINALIZE_SILENCE: usize = 11_200;
    const O_MAX_SEGMENT: usize = 6 * 16_000;
    const O_FINAL_TAIL: usize = 3_200;
    const O_MIN_SEGMENT: usize = 4_000;

    #[derive(Default)]
    struct Oracle {
        segment: Vec<f32>,
        preroll: Vec<f32>,
        has_speech: bool,
        silence_run: usize,
    }

    impl Oracle {
        fn push(&mut self, input: &[f32]) -> Option<Vec<f32>> {
            let len = input.len();
            let rms = (input.iter().map(|&x| x * x).sum::<f32>() / len as f32).sqrt();
            let is_sound = rms >= O_SPEECH_RMS;

            if !self.has_speech {
                if is_sound {
                    self.segment = std::mem::take(&mut self.preroll);
                    self.segment.extend_from_slice(input);
                    self.has_speech = true;
                    self.silence_run = 0;
                } else {
                    self.preroll.extend_from_slice(input);
                    let excess = self.preroll.len().saturating_sub(O_PREROLL);
                    if excess > 0 {
                        self.preroll.drain(..excess);
                    }
                    return None;
                }
            } else {
                self.segment.extend_from_slice(input);
                if is_sound {
                    self.silence_run = 0;
                } else {
                    self.silence_run += len;
                }
            }

            let silence_done = self.silence_run >= O_FINALIZE_SILENCE;
            if silence_done || self.segment.len() >= O_MAX_SEGMENT {
                let mut samples = std::mem::take(&mut self.segment);
                if silence_done {
                    let keep = samples.len() - self.silence_run + O_FINAL_TAIL;
                    samples.truncate(keep.min(samples.len()));
                    self.has_speech = false;
                    self.preroll.clear();
                } else {
                    let from = samples.len().saturating_sub(3 * 16_000);
                    let split = find_quietest_split(&samples, from, samples.len());
                    self.segment = samples.split_off(split);
                }
                self.silence_run = 0;
                if samples.len() >= O_MIN_SEGMENT {
                    return Some(samples);
                }
            }
            None
        }
    }

    // --- synthetic audio --------------------------------------------------

    /// Deterministic pseudo-random, so a failure is reproducible.
    fn lcg(seed: &mut u64) -> u64 {
        *seed = seed
            .wrapping_mul(6364136223846793005)
            .wrapping_add(1442695040888963407);
        *seed >> 33
    }

    /// A 200 Hz tone of the given peak amplitude; RMS is amplitude / √2.
    fn tone(secs: f32, amplitude: f32) -> Vec<f32> {
        let n = (secs * SAMPLE_RATE as f32) as usize;
        (0..n)
            .map(|i| (i as f32 * 0.0785).sin() * amplitude)
            .collect()
    }

    /// A voiced carrier with a syllable-like envelope. The deep, brief valleys
    /// keep this closer to real speech than a constant laboratory tone.
    fn speech(secs: f32) -> Vec<f32> {
        speech_at(secs, 0.1)
    }

    /// Speech at the level a phone microphone actually receives in a given
    /// room: about 12 dB over the background, never below the standard test
    /// voice. A person in a car raises their voice; they do not dictate at
    /// bedroom volume into a 0.06 RMS bed, and no gauge could recover that.
    fn speech_over(secs: f32, room: f32) -> Vec<f32> {
        speech_at(secs, (room * 4.0 * std::f32::consts::SQRT_2).max(0.1))
    }

    fn speech_at(secs: f32, peak: f32) -> Vec<f32> {
        let n = (secs * SAMPLE_RATE as f32) as usize;
        (0..n)
            .map(|i| {
                let envelope = 0.03 + 0.97 * ((i as f32 * 0.0012).sin() * 0.5 + 0.5).powi(4);
                (i as f32 * 0.0785).sin() * peak * envelope
            })
            .collect()
    }

    /// Below both gauges (RMS ≈ 0.00035).
    fn quiet(secs: f32) -> Vec<f32> {
        tone(secs, 0.0005)
    }

    /// Background at a given RMS: low-passed pseudo-random noise, the shape a
    /// fan, a car or a cafe has rather than a tone. Deterministic.
    fn noise(secs: f32, target_rms: f32, seed: u64) -> Vec<f32> {
        let n = (secs * SAMPLE_RATE as f32) as usize;
        let mut s = seed;
        let mut lp = 0.0f32;
        let raw: Vec<f32> = (0..n)
            .map(|_| {
                let white = (lcg(&mut s) as f32 / (1u64 << 30) as f32) - 1.0;
                lp = lp * 0.97 + white * 0.03;
                lp
            })
            .collect();
        let have = rms(&raw);
        if have == 0.0 {
            return raw;
        }
        raw.iter().map(|&x| x * target_rms / have).collect()
    }

    /// The level above which the test audio counts as speech, used by the
    /// coverage assertions below.
    const TEST_SOUND_RMS: f32 = 0.02;

    fn cat(parts: &[Vec<f32>]) -> Vec<f32> {
        parts.iter().flat_map(|p| p.iter().copied()).collect()
    }

    /// Lays a signal over a background bed of the same length.
    fn over(signal: &[f32], bed: &[f32]) -> Vec<f32> {
        signal
            .iter()
            .zip(bed.iter())
            .map(|(&a, &b)| a + b)
            .collect()
    }

    fn feed(seg: &mut Segmenter, audio: &[f32], block: usize) -> Vec<Piece> {
        let mut out = Vec::new();
        for chunk in audio.chunks(block) {
            if let Some(p) = seg.push(chunk) {
                out.push(p);
            }
        }
        out
    }

    fn feed_oracle(audio: &[f32], block: usize) -> Vec<Vec<f32>> {
        let mut o = Oracle::default();
        let mut out = Vec::new();
        for chunk in audio.chunks(block) {
            if let Some(p) = o.push(chunk) {
                out.push(p);
            }
        }
        out
    }

    fn dictation() -> Segmenter {
        // Most segmentation characterization cases use the original fast
        // boundary so they stay small. Production uses the adjustable 3 s
        // default, covered separately below.
        Segmenter::new(SegmenterConfig::dictation_with_split_seconds(1.5))
    }

    fn causes(pieces: &[Piece]) -> Vec<CutCause> {
        pieces.iter().map(|p| p.cause).collect()
    }

    /// Piece end offsets in seconds, rounded to 0.1 s.
    fn boundaries(pieces: &[Piece]) -> Vec<f32> {
        pieces
            .iter()
            .map(|p| (p.end() as f32 / SAMPLE_RATE as f32 * 10.0).round() / 10.0)
            .collect()
    }

    // --- characterization: the extraction preserves behaviour -------------

    fn assert_parity(audio: &[f32], block: usize, label: &str) {
        let mut seg = Segmenter::new(SegmenterConfig::subtitles());
        let mine = feed(&mut seg, audio, block);
        let theirs = feed_oracle(audio, block);
        assert_eq!(
            mine.len(),
            theirs.len(),
            "{}: piece count differs (extracted {:?} vs original {:?})",
            label,
            mine.iter().map(|p| p.samples.len()).collect::<Vec<_>>(),
            theirs.iter().map(|p| p.len()).collect::<Vec<_>>()
        );
        for (i, (a, b)) in mine.iter().zip(theirs.iter()).enumerate() {
            assert_eq!(
                a.samples.len(),
                b.len(),
                "{}: piece {} length differs",
                label,
                i
            );
            assert_eq!(&a.samples, b, "{}: piece {} content differs", label, i);
        }
    }

    #[test]
    fn extraction_matches_subtitle_rs_on_speech_and_silence() {
        let audio = cat(&[
            quiet(1.0),
            speech(2.0),
            quiet(1.5),
            speech(3.0),
            quiet(0.5),
            speech(1.0),
            quiet(2.0),
        ]);
        for block in [256usize, 512, 1024, 1600, 4096] {
            assert_parity(&audio, block, &format!("block {}", block));
        }
    }

    #[test]
    fn extraction_matches_subtitle_rs_when_the_cap_forces_a_cut() {
        // 20 s of unbroken speech: the 6 s caption cap fires three times, each
        // time splitting at the quietest point and carrying the remainder.
        let audio = cat(&[quiet(0.5), speech(20.0), quiet(1.5)]);
        for block in [512usize, 1024, 1600] {
            assert_parity(&audio, block, &format!("cap, block {}", block));
        }
    }

    #[test]
    fn extraction_matches_subtitle_rs_on_edge_shapes() {
        let cases: Vec<(&str, Vec<f32>)> = vec![
            ("silence only", quiet(5.0)),
            ("speech only", speech(5.0)),
            (
                "speech from the first sample",
                cat(&[speech(2.0), quiet(2.0)]),
            ),
            ("clipped tail", cat(&[quiet(0.5), speech(2.0)])),
            (
                "blip shorter than the minimum piece",
                cat(&[quiet(1.0), speech(0.02), quiet(2.0)]),
            ),
            // No pre-roll has accumulated yet, so this one really does fall
            // under the minimum length and must be dropped by both.
            ("blip before any pre-roll", cat(&[speech(0.01), quiet(2.0)])),
            (
                "pause exactly at the threshold",
                cat(&[speech(1.0), quiet(0.7), speech(1.0), quiet(1.0)]),
            ),
        ];
        for (label, audio) in cases {
            for block in [128usize, 512, 1024] {
                assert_parity(&audio, block, &format!("{} block {}", label, block));
            }
        }
    }

    #[test]
    fn extraction_matches_subtitle_rs_on_random_block_plans() {
        let mut seed = 0x5eed_1234u64;
        for case in 0..40 {
            let mut audio = Vec::new();
            let steps = 6 + (lcg(&mut seed) % 8) as usize;
            for _ in 0..steps {
                let secs = 0.05 + (lcg(&mut seed) % 400) as f32 / 100.0;
                if lcg(&mut seed) % 2 == 0 {
                    audio.extend(speech(secs));
                } else {
                    audio.extend(quiet(secs));
                }
            }
            let block = [128usize, 333, 512, 1024, 1600][(lcg(&mut seed) % 5) as usize];
            assert_parity(
                &audio,
                block,
                &format!("random case {} block {}", case, block),
            );
        }
    }

    #[test]
    fn the_parity_check_can_fail() {
        // The check above is worth nothing unless a changed threshold breaks
        // it. Drive the same audio with a deliberately wrong finalize
        // threshold and require a different answer.
        let audio = cat(&[quiet(0.5), speech(2.0), quiet(1.0), speech(2.0), quiet(1.5)]);
        let mut cfg = SegmenterConfig::subtitles();
        cfg.finalize_silence_samples = 24_000; // the dictation value
        let mut seg = Segmenter::new(cfg);
        let mine = feed(&mut seg, &audio, 1024);
        let theirs = feed_oracle(&audio, 1024);
        assert_ne!(
            mine.len(),
            theirs.len(),
            "a wrong threshold must make the parity check fail"
        );
    }

    // --- the dictation rules ----------------------------------------------

    #[test]
    fn a_one_second_pause_is_not_a_boundary() {
        let audio = cat(&[quiet(0.5), speech(3.0), quiet(1.0), speech(3.0)]);
        let mut seg = dictation();
        let pieces = feed(&mut seg, &audio, 1024);
        assert!(
            pieces.is_empty(),
            "a 1.0 s pause must not split: {:?}",
            causes(&pieces)
        );
        assert!(seg.flush().is_some());
    }

    #[test]
    fn a_short_utterance_is_joined_to_the_next_piece_and_never_lost() {
        // "Yes." followed by a pause and then a sentence. Below about 1.5 s of
        // speech the model stops getting the words right, so the fragment is
        // not transcribed on its own, so it goes at the front of the next piece
        // rather than into the bin. The joined audio is the two real stretches
        // back to back with the real room between them; nothing is padded.
        let audio = cat(&[quiet(0.5), speech(0.8), quiet(2.5), speech(2.0), quiet(2.5)]);
        let mut seg = dictation();
        let mut pieces = feed(&mut seg, &audio, 1024);
        pieces.extend(seg.flush());
        assert_eq!(
            pieces.len(),
            2,
            "got {:?} at {:?} s",
            causes(&pieces),
            boundaries(&pieces)
        );
        let joined = &pieces[0];
        assert_eq!(joined.start, 0, "the piece should start at the fragment");
        let secs = joined.samples.len() as f32 / SAMPLE_RATE as f32;
        // Exact retention keeps the whole interval through the pause that
        // joins the fragment to the next utterance.
        assert!(
            (7.3..7.5).contains(&secs),
            "the joined piece holds {:.2} s; expected the whole recorded interval",
            secs
        );
        assert_eq!(joined.pause_before, 0.0, "it is still the first piece");
        assert_eq!(pieces[1].start, joined.end());
        assert_eq!(pieces[1].end(), audio.len() as u64);
    }

    #[test]
    fn every_piece_keeps_a_wide_quiet_margin_around_the_speech() {
        // A word that sits hard against a cut comes back damaged: on the real
        // model a boundary with 60 ms of quiet after the preceding word turned
        // the name "Neuphonic" into "Nephonic" and "no phonic", and once
        // deleted it. The margin is what stops that, so it is asserted, not
        // left to the constants.
        let audio = cat(&[quiet(1.0), speech(2.0), quiet(2.5), speech(2.0), quiet(2.5)]);
        let mut seg = dictation();
        let mut pieces = feed(&mut seg, &audio, 1024);
        pieces.extend(seg.flush());
        assert_eq!(pieces.len(), 3, "got {:?}", causes(&pieces));
        let speech_spans = [(1.0f32, 3.0f32), (5.5, 7.5)];
        for (piece, (from, to)) in pieces.iter().take(2).zip(speech_spans) {
            let start = piece.start as f32 / SAMPLE_RATE as f32;
            let end = piece.end() as f32 / SAMPLE_RATE as f32;
            assert!(
                from - start >= 0.45,
                "only {:.2} s of quiet kept before the speech at {} s",
                from - start,
                from
            );
            assert!(
                end - to >= 0.45,
                "only {:.2} s of quiet kept after the speech ending at {} s",
                end - to,
                to
            );
        }
    }

    #[test]
    fn a_two_second_pause_is_a_boundary() {
        let audio = cat(&[quiet(0.5), speech(3.0), quiet(2.0), speech(3.0)]);
        let mut seg = dictation();
        let pieces = feed(&mut seg, &audio, 1024);
        assert_eq!(pieces.len(), 1, "a 2.0 s pause must split once");
        assert_eq!(pieces[0].cause, CutCause::Silence);
        let tail = seg.flush().expect("the second half is still open");
        assert_eq!(tail.cause, CutCause::Flush);
    }

    #[test]
    fn the_boundary_threshold_is_the_configured_one() {
        let block = SAMPLE_RATE / 10; // 0.1 s blocks, so the pause is exact
        for (pause, want_split) in [(1.4f32, false), (1.5, true), (1.6, true)] {
            let audio = cat(&[quiet(0.5), speech(2.0), quiet(pause), speech(2.0)]);
            let mut seg = dictation();
            let pieces = feed(&mut seg, &audio, block);
            assert_eq!(
                !pieces.is_empty(),
                want_split,
                "1.5 s setting, pause of {} s: expected split = {}",
                pause,
                want_split
            );
        }
        // ...and the setting moves it: at 3 s a 2 s pause no longer splits and
        // a 3.5 s one does.
        for (pause, want_split) in [(2.0f32, false), (3.5, true)] {
            let audio = cat(&[quiet(0.5), speech(2.0), quiet(pause), speech(2.0)]);
            let mut seg = Segmenter::new(SegmenterConfig {
                finalize_silence_samples: 3 * SAMPLE_RATE,
                ..SegmenterConfig::dictation()
            });
            let pieces = feed(&mut seg, &audio, block);
            assert_eq!(
                !pieces.is_empty(),
                want_split,
                "3 s threshold, pause of {} s: expected split = {}",
                pause,
                want_split
            );
        }
    }

    #[test]
    fn speech_without_a_pause_is_never_cut() {
        // Four minutes without a configured pause is one piece.
        let mut seg = dictation();
        let pieces = feed(&mut seg, &cat(&[quiet(0.5), speech(240.0)]), 1024);
        assert!(
            pieces.is_empty(),
            "four minutes of unbroken speech must not be cut, got {:?} at {:?} s",
            causes(&pieces),
            boundaries(&pieces)
        );
        // Now let him stop. The piece arrives on the pause, not on the flush.
        let mut pieces = feed(&mut seg, &quiet(2.0), 1024);
        assert_eq!(pieces.len(), 1, "one piece holding the lot");
        assert_eq!(pieces[0].cause, CutCause::Silence);
        let secs = pieces[0].samples.len() as f32 / SAMPLE_RATE as f32;
        assert!(
            (241.9..242.1).contains(&secs),
            "the single piece should hold the whole utterance, holds {:.1} s",
            secs
        );
        pieces.extend(seg.flush());
        assert_eq!(pieces[1].start, pieces[0].end());
        assert_eq!(pieces[1].end(), (242.5 * SAMPLE_RATE as f32) as u64);
    }

    #[test]
    fn unbroken_speech_over_background_noise_is_still_never_cut() {
        // The same, with a room from a quiet bedroom up to a moving car. This
        // is where an estimator built from all recent blocks, rather than from
        // the non-speech ones, invents a pause and can cut a sentence in half.
        for level in [0.002f32, 0.02, 0.05] {
            let dry = cat(&[quiet(0.5), speech_over(60.0, level)]);
            let bed = noise(dry.len() as f32 / SAMPLE_RATE as f32, level, 0xB16_0001);
            let audio = over(&dry, &bed);
            let mut seg = dictation();
            let pieces = feed(&mut seg, &audio, 1024);
            assert!(
                pieces.is_empty(),
                "room at {}: unbroken speech was cut at {:?} s",
                level,
                boundaries(&pieces)
            );
        }
    }

    // --- the room gauge ---------------------------------------------------

    /// Speech and pauses over a background bed, with the pauses at known
    /// places: 3 s of speech, 2 s pause, 4 s speech, 2 s pause, 2 s speech.
    fn noisy_dictation(level: f32) -> Vec<f32> {
        let dry = cat(&[
            quiet(0.6),
            speech_over(3.0, level),
            quiet(2.0),
            speech_over(4.0, level),
            quiet(2.0),
            speech_over(2.0, level),
            quiet(2.0),
        ]);
        let bed = noise(dry.len() as f32 / SAMPLE_RATE as f32, level, 0x5ee_d10);
        over(&dry, &bed)
    }

    #[test]
    fn a_useful_pause_is_found_at_every_background_level() {
        // 0.0005 is a quiet room. 0.02 and 0.06 are five and fifteen times the
        // fixed caption threshold: a fan, and a moving car.
        for level in [0.0005f32, 0.004, 0.02, 0.06] {
            let audio = noisy_dictation(level);
            let mut seg = dictation();
            let mut pieces = feed(&mut seg, &audio, 1024);
            pieces.extend(seg.flush());
            assert!(
                pieces
                    .iter()
                    .filter(|p| p.cause == CutCause::Silence)
                    .count()
                    >= 1,
                "background {}: expected work before stop, got {:?} at {:?} s",
                level,
                causes(&pieces),
                boundaries(&pieces)
            );
            assert_eq!(pieces.first().map(|p| p.start), Some(0));
            assert_eq!(pieces.last().map(Piece::end), Some(audio.len() as u64));
            assert!(pieces.windows(2).all(|w| w[0].end() == w[1].start));
        }
    }

    #[test]
    fn the_fixed_caption_gauge_finds_nothing_in_the_same_noisy_audio() {
        // The failing half of the check above. Without it the previous test
        // proves only that something works, not that the room-relative part is
        // what makes it work. A fixed threshold sees one unbroken stretch of
        // speech and, with no length cap, would never cut at all.
        for level in [0.02f32, 0.06] {
            let audio = noisy_dictation(level);
            let mut seg = Segmenter::new(SegmenterConfig {
                gauge: SpeechGauge::Absolute { rms: 0.004 },
                ..SegmenterConfig::dictation()
            });
            let pieces = feed(&mut seg, &audio, 1024);
            assert!(
                pieces.is_empty(),
                "background {}: the fixed gauge is expected to miss every pause; \
                 if it no longer does, this audio no longer reproduces the defect",
                level
            );
        }
    }

    #[test]
    fn a_room_that_is_loud_from_the_first_sample_is_still_a_room() {
        // No quiet lead-in: the noise is there before the recording starts,
        // which is what happens when the phone is already in the car.
        let room = 0.05;
        let dry = cat(&[
            quiet(0.6),
            speech_over(3.0, room),
            quiet(2.0),
            speech_over(3.0, room),
            quiet(2.0),
        ]);
        let bed = noise(dry.len() as f32 / SAMPLE_RATE as f32, room, 0xCA5);
        let mut seg = dictation();
        let mut pieces = feed(&mut seg, &over(&dry, &bed), 1024);
        pieces.extend(seg.flush());
        assert_eq!(
            pieces.len(),
            2,
            "expected two pieces, got {:?} at {:?} s",
            causes(&pieces),
            boundaries(&pieces)
        );
    }

    #[test]
    fn hysteresis_keeps_a_borderline_frame_inside_speech() {
        let mut one = SegmenterConfig::dictation();
        match &mut one.gauge {
            SpeechGauge::Room {
                enter_ratio,
                leave_ratio,
                ..
            } => *leave_ratio = *enter_ratio,
            _ => unreachable!("dictation uses the room gauge"),
        }

        let check = |cfg: SegmenterConfig| {
            let mut seg = Segmenter::new(cfg);
            seg.room_level = Some(0.01);
            seg.in_speech = true;
            seg.is_sound(&tone(512.0 / SAMPLE_RATE as f32, 0.035))
        };
        assert!(check(SegmenterConfig::dictation()));
        assert!(!check(one));
    }

    #[test]
    fn a_dead_lead_in_does_not_blind_the_gauge() {
        // Android hands out zero-filled buffers while the capture stream
        // starts, and prepared clips often open the same way. Those samples
        // are not a room, and letting them into the estimate put it near zero,
        // after which every later frame stood far above it, the whole session
        // read as speech and not one pause was found. This is the fixture for
        // the failure `bench.wav` actually showed.
        let dead = vec![0.0f32; (0.4 * SAMPLE_RATE as f32) as usize];
        let room = 0.002;
        let dry = cat(&[
            quiet(0.6),
            speech_over(2.0, room),
            quiet(2.5),
            speech_over(2.0, room),
            quiet(2.5),
        ]);
        let bed = noise(dry.len() as f32 / SAMPLE_RATE as f32, room, 0xDEAD);
        let audio = cat(&[dead, over(&dry, &bed)]);

        let mut seg = dictation();
        let mut pieces = feed(&mut seg, &audio, 1024);
        pieces.extend(seg.flush());
        assert_eq!(
            pieces.len(),
            2,
            "a dead lead-in must not cost the session its pauses, got {:?} at {:?} s",
            causes(&pieces),
            boundaries(&pieces)
        );
    }

    #[test]
    fn a_background_that_rises_mid_session_still_finds_later_pauses() {
        let quiet_room = 0.001;
        let loud_room = 0.02;
        let first = {
            let dry = cat(&[quiet(0.6), speech_over(2.0, quiet_room), quiet(2.5)]);
            let bed = noise(dry.len() as f32 / SAMPLE_RATE as f32, quiet_room, 0xF1);
            over(&dry, &bed)
        };
        let second = {
            let dry = cat(&[
                speech_over(3.0, loud_room),
                quiet(2.5),
                speech_over(3.0, loud_room),
                quiet(2.5),
            ]);
            let bed = noise(dry.len() as f32 / SAMPLE_RATE as f32, loud_room, 0xF2);
            over(&dry, &bed)
        };
        let audio = cat(&[first, second]);

        let quiet_part = 5.1; // 0.6 + 2.0 + 2.5
        let mut seg = dictation();
        let mut pieces = feed(&mut seg, &audio, 1024);
        pieces.extend(seg.flush());
        assert!(boundaries(&pieces)[0] < quiet_part);
        assert!(
            pieces
                .iter()
                .filter(|p| p.cause == CutCause::Silence)
                .count()
                >= 2,
            "the detector should recover after the room changes, got {:?} at {:?} s",
            causes(&pieces),
            boundaries(&pieces)
        );
    }

    #[test]
    fn the_room_window_stays_bounded_however_long_the_session_runs() {
        // The estimate sorts its window on every frame, inside the microphone
        // callback. Expiring the window by time is what keeps that a fixed
        // cost: without it the window grows for as long as the session does,
        // the per-frame sort grows with it, and a long dictation ends up doing
        // that work in the one place that must not stall, because a stalled
        // callback drops microphone samples.
        let bed = noise(120.0, 0.004, 0x10_4EE);
        let mut dry = quiet(0.5);
        for _ in 0..20 {
            dry.extend(speech(3.0));
            dry.extend(quiet(2.5));
        }
        dry.truncate(bed.len());
        let mut seg = dictation();
        feed(&mut seg, &over(&dry, &bed), 1024);
        let window = match SegmenterConfig::dictation().gauge {
            SpeechGauge::Room {
                window_samples,
                frame_samples,
                ..
            } => window_samples / frame_samples,
            _ => unreachable!(),
        };
        assert!(
            seg.room.len() <= window + 1,
            "after {} s the window holds {} frames; it should hold at most {}",
            dry.len() / SAMPLE_RATE,
            seg.room.len(),
            window + 1
        );
    }

    #[test]
    fn a_session_that_starts_in_the_middle_of_a_word_recovers() {
        // Speech from sample zero, so the seed window holds no room at all:
        // the first half second of *speech* is what seeds the room estimate,
        // and until real silence arrives the estimate is far too high. The
        // known cost is the first boundary, which this audio's gauge misses.
        // The safe direction is a missed boundary and one longer piece, but it
        // is a real limit and it is written
        // down here rather than hidden in a looser assertion. What must not
        // happen is missing *every* boundary, which is the whole feature
        // silently doing nothing.
        let audio = cat(&[
            speech(3.0),
            quiet(2.0),
            speech(3.0),
            quiet(2.0),
            speech(2.0),
            quiet(2.0),
        ]);
        let mut seg = dictation();
        let mut pieces = feed(&mut seg, &audio, 1024);
        pieces.extend(seg.flush());
        assert!(
            pieces.len() >= 2,
            "expected the gauge to recover and find the later pauses, got {:?} at {:?} s",
            causes(&pieces),
            boundaries(&pieces)
        );
        assert!(
            boundaries(&pieces)[0] <= 9.0,
            "recovery should arrive by the second pause, first cut at {:?} s",
            boundaries(&pieces)
        );
    }

    // --- the pause reported with each piece -------------------------------

    #[test]
    fn each_piece_reports_the_silence_that_came_before_it() {
        let audio = cat(&[
            quiet(0.5),
            speech(2.0),
            quiet(2.0),
            speech(2.0),
            quiet(3.0),
            speech(2.0),
            quiet(1.0),
        ]);
        let mut seg = dictation();
        let mut pieces = feed(&mut seg, &audio, 1024);
        pieces.extend(seg.flush());
        assert_eq!(pieces.len(), 3, "got {:?}", causes(&pieces));
        assert_eq!(
            pieces[0].pause_before, 0.0,
            "nothing came before the first piece"
        );
        assert!(
            (pieces[1].pause_before - 2.0).abs() < 0.15,
            "second piece should report the 2 s pause, reported {:.2}",
            pieces[1].pause_before
        );
        assert!(
            (pieces[2].pause_before - 3.0).abs() < 0.15,
            "third piece should report the 3 s pause, reported {:.2}",
            pieces[2].pause_before
        );
    }

    #[test]
    fn the_reported_pause_tells_a_hesitation_from_a_sentence_end() {
        // The whole point of reporting it: the consumer applies a larger
        // threshold to decide whether a boundary is a new sentence, so a 2 s
        // gap and a 5 s gap must not report the same number.
        let mk = |gap: f32| {
            let audio = cat(&[quiet(0.5), speech(2.0), quiet(gap), speech(2.0), quiet(2.0)]);
            let mut seg = dictation();
            let mut pieces = feed(&mut seg, &audio, 1024);
            pieces.extend(seg.flush());
            pieces[1].pause_before
        };
        let short = mk(2.0);
        let long = mk(5.0);
        assert!(
            (short - 2.0).abs() < 0.15 && (long - 5.0).abs() < 0.15,
            "reported {:.2} and {:.2} for 2 s and 5 s gaps",
            short,
            long
        );
    }

    // --- invariants -------------------------------------------------------

    #[test]
    fn every_captured_sample_comes_back_in_exactly_one_piece() {
        let audio = cat(&[
            quiet(1.0),
            speech(4.0),
            quiet(2.0),
            speech(25.0),
            quiet(1.0),
            speech(40.0),
            quiet(0.3),
            speech(2.0),
            quiet(2.0),
        ]);
        let mut seg = dictation();
        let mut pieces = feed(&mut seg, &audio, 1024);
        pieces.extend(seg.flush());

        let mut prev_end = 0u64;
        for p in &pieces {
            assert_eq!(p.start, prev_end, "pieces overlap or leave a gap");
            let s = p.start as usize;
            assert_eq!(
                &audio[s..s + p.samples.len()],
                &p.samples[..],
                "piece at {} is not the input it claims to be",
                s
            );
            prev_end = p.end();
        }
        assert_eq!(prev_end as usize, audio.len(), "captured tail was dropped");
    }

    #[test]
    fn cuts_land_in_quiet_audio() {
        let audio = cat(&[
            quiet(0.5),
            speech(3.0),
            quiet(2.0),
            speech(17.0),
            quiet(2.0),
            speech(3.5),
            quiet(2.0),
        ]);
        let mut seg = dictation();
        let pieces = feed(&mut seg, &audio, 1024);
        assert!(pieces.len() >= 2);
        for p in &pieces {
            assert_eq!(p.cause, CutCause::Silence, "dictation makes no other cut");
            let at = p.end() as usize;
            let from = at.saturating_sub(800);
            let to = (at + 800).min(audio.len());
            assert!(
                rms(&audio[from..to]) < TEST_SOUND_RMS,
                "cut at {:.2} s is not in quiet audio",
                at as f32 / SAMPLE_RATE as f32
            );
        }
    }

    #[test]
    fn flush_keeps_the_captured_tail() {
        let audio = cat(&[quiet(0.5), speech(2.0), quiet(1.2)]);
        let mut seg = dictation();
        assert!(feed(&mut seg, &audio, 1024).is_empty());
        let tail = seg.flush().expect("the open piece");
        assert_eq!(tail.start, 0);
        assert_eq!(tail.samples, audio);
    }

    #[test]
    fn a_session_too_quiet_to_detect_is_still_transcribed_once() {
        // Nothing crosses the gauge. Without the retain rule the whole session
        // would be silently lost; with it, it comes back once at the end,
        // which is exactly what the app did before streaming.
        let audio = quiet(8.0);
        let mut seg = dictation();
        assert!(feed(&mut seg, &audio, 1024).is_empty());
        let tail = seg
            .flush()
            .expect("a too-quiet session must still produce audio");
        assert_eq!(tail.samples.len(), audio.len());
        assert_eq!(tail.start, 0);
    }

    #[test]
    fn seventy_seconds_below_the_gauge_are_not_front_trimmed() {
        let audio = quiet(70.0);
        let mut seg = dictation();
        assert!(feed(&mut seg, &audio, 4096).is_empty());
        let tail = seg.flush().expect("the full quiet capture");
        assert_eq!(tail.start, 0);
        assert_eq!(tail.samples, audio);
    }

    #[test]
    fn an_empty_session_produces_nothing() {
        let mut seg = dictation();
        assert!(seg.flush().is_none());
        assert_eq!(seg.total_pushed(), 0);
    }

    #[test]
    fn dictation_split_pause_is_adjustable_and_invalid_values_use_default() {
        assert_eq!(
            SegmenterConfig::dictation_with_split_seconds(1.5).finalize_silence_samples,
            24_000
        );
        assert_eq!(
            SegmenterConfig::dictation_with_split_seconds(8.0).finalize_silence_samples,
            128_000
        );
        assert_eq!(
            SegmenterConfig::dictation_with_split_seconds(f32::NAN).finalize_silence_samples,
            (DEFAULT_SPLIT_SECONDS * SAMPLE_RATE as f32) as usize
        );
    }

    #[test]
    fn a_second_session_after_reset_behaves_like_the_first() {
        let audio = cat(&[quiet(0.5), speech(3.0), quiet(2.0), speech(1.0)]);
        let mut seg = dictation();
        let first = feed(&mut seg, &audio, 1024);
        let first_tail = seg.flush();
        seg.reset();
        let second = feed(&mut seg, &audio, 1024);
        let second_tail = seg.flush();
        assert_eq!(causes(&first), causes(&second));
        assert_eq!(
            first.iter().map(|p| p.start).collect::<Vec<_>>(),
            second.iter().map(|p| p.start).collect::<Vec<_>>()
        );
        assert_eq!(
            first_tail.map(|p| p.samples.len()),
            second_tail.map(|p| p.samples.len())
        );
    }

    #[test]
    fn the_block_size_does_not_change_the_answer() {
        // The microphone's block size is not ours to choose, and the room
        // estimate is built per block, so the answer must not depend on it.
        let audio = noisy_dictation(0.02);
        let mut reference: Option<Vec<f32>> = None;
        for block in [128usize, 512, 1024, 1600, 4096] {
            let mut seg = dictation();
            let mut pieces = feed(&mut seg, &audio, block);
            pieces.extend(seg.flush());
            assert_eq!(pieces.first().map(|p| p.start), Some(0));
            assert_eq!(pieces.last().map(Piece::end), Some(audio.len() as u64));
            assert!(pieces.windows(2).all(|w| w[0].end() == w[1].start));
            let b = boundaries(&pieces);
            match &reference {
                None => reference = Some(b),
                Some(r) => assert!(
                    r.len() == b.len() && r.iter().zip(b.iter()).all(|(x, y)| (x - y).abs() <= 0.7),
                    "block {}: boundaries moved from {:?} to {:?}",
                    block,
                    r,
                    b
                ),
            }
        }
    }
}
