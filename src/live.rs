//! Live transcription of a voice-input recording with a streaming model
//! (transcribe.cpp's streaming API, e.g. Nemotron Speech Streaming).
//!
//! A worker thread follows the recording's audio buffer and feeds new samples
//! into a stream while the user speaks, reporting the text so far through the
//! target's `onStreamText(committed, tentative)` callback: `committed` only
//! ever grows (later reports extend it), `tentative` is the model's current
//! guess for what follows and may be revised. When the recording stops, only
//! the audio still buffered in the stream needs decoding; the full text then
//! goes out through `onTextTranscribed`, as after a batch transcription.
//!
//! Models without streaming support, and recordings that stop before the
//! engine could begin a stream, are transcribed in batch as before: the
//! worker steps aside and [`LiveStream::finish`] tells voice_session.rs so.

use std::sync::{Arc, Condvar, Mutex, Weak};
use once_cell::sync::Lazy;
use std::time::Duration;

use jni::objects::{GlobalRef, JObject};
use jni::JNIEnv;

use crate::engine::{self, InteractiveTurn};

/// How often the worker picks up newly recorded audio.
const FEED_INTERVAL: Duration = Duration::from_millis(100);
/// Longest piece of audio fed in one call (1 s), so a backlog (audio recorded
/// while the model was still loading) keeps the text and cancel responsive.
const MAX_FEED_SAMPLES: usize = 16_000;

#[derive(Clone, Copy, PartialEq, Debug)]
enum Mode {
    /// The worker is still preparing the stream (loading the model, waiting
    /// for the engine).
    Pending,
    /// The stream is running; the worker delivers the result.
    Streaming,
    /// No stream: the recording is transcribed in batch when it stops.
    Batch,
    Done,
}

#[derive(Clone, Copy, PartialEq, Debug)]
enum End {
    /// The recording stopped: decode the rest and deliver the text.
    Finish,
    /// The recording was discarded: deliver nothing.
    Cancel,
}

struct Control {
    mode: Mode,
    end: Option<End>,
}

/// One recording's live transcription.
pub struct LiveStream {
    control: Mutex<Control>,
    wake: Condvar,
}

// A new voice request asks an older live recording to stop before taking the
// shared engine. This also lets model reload release old model caches.
type Active = (Weak<LiveStream>, Arc<jni::JavaVM>, GlobalRef);
static ACTIVE: Lazy<Mutex<Option<Active>>> = Lazy::new(|| Mutex::new(None));

pub fn finish_active() {
    let previous = ACTIVE.lock().unwrap().clone();
    if let Some((weak, jvm, target)) = previous {
        if weak.upgrade().is_some_and(|live| {
            let c = live.control.lock().unwrap();
            c.end.is_none() && matches!(c.mode, Mode::Pending | Mode::Streaming)
        }) {
            if let Ok(mut env) = jvm.attach_current_thread() {
                let _ = env.call_method(target.as_obj(), "onAutoStop", "()V", &[]);
                clear_exception(&mut env);
            }
        }
    }
}

impl LiveStream {
    /// Starts following `audio` (the recording's buffer, which only grows
    /// until the recording stops) on a worker thread.
    pub fn start(
        jvm: Arc<jni::JavaVM>,
        target_ref: GlobalRef,
        audio: Arc<Mutex<Vec<f32>>>,
    ) -> Arc<LiveStream> {
        finish_active();
        let live = Arc::new(LiveStream {
            control: Mutex::new(Control {
                mode: Mode::Pending,
                end: None,
            }),
            wake: Condvar::new(),
        });
        *ACTIVE.lock().unwrap() = Some((Arc::downgrade(&live), jvm.clone(), target_ref.clone()));
        let worker = live.clone();
        std::thread::spawn(move || {
            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                worker.run(&jvm, &target_ref, &audio)
            }));
            if result.is_err() {
                log::error!("live transcription panicked");
                // A panic before the stream ran leaves the recording to the
                // batch path; one while streaming ends it like a failed stream.
                if !worker.step_aside() {
                    if let Ok(mut env) = jvm.attach_current_thread() {
                        let obj = target_ref.as_obj();
                        while worker.wait().is_none() {}
                        if !worker.cancelled() {
                            notify_status(&mut env, obj, "Error: live transcription failed");
                        }
                        worker.control.lock().unwrap().mode = Mode::Done;
                    }
                }
            }
            let mut active = ACTIVE.lock().unwrap();
            if active.as_ref().is_some_and(|(weak, _, _)|
                weak.ptr_eq(&Arc::downgrade(&worker))) {
                *active = None;
            }
        });
        live
    }

    /// The recording stopped (its audio buffer is complete). Returns true if
    /// the stream delivers the transcription, false if the caller must
    /// transcribe the recording in batch.
    pub fn finish(&self) -> bool {
        let mut control = self.control.lock().unwrap();
        match control.mode {
            Mode::Streaming => {
                control.end = Some(End::Finish);
                self.wake.notify_all();
                true
            }
            Mode::Pending | Mode::Batch | Mode::Done => {
                control.mode = Mode::Batch;
                false
            }
        }
    }

    /// The recording was discarded: abandon the stream without output.
    pub fn cancel(&self) {
        let mut control = self.control.lock().unwrap();
        if control.mode == Mode::Pending {
            control.mode = Mode::Batch;
        }
        control.end = Some(End::Cancel);
        self.wake.notify_all();
    }

    /// Leaves the recording to the batch path unless the stream already
    /// runs. Returns false if it did (the worker must deliver the result).
    fn step_aside(&self) -> bool {
        let mut control = self.control.lock().unwrap();
        if control.mode == Mode::Streaming {
            return false;
        }
        control.mode = Mode::Batch;
        true
    }

    /// Switches to streaming unless the recording already ended (or went to
    /// the batch path) while the stream was being prepared.
    fn claim(&self) -> bool {
        let mut control = self.control.lock().unwrap();
        if control.mode != Mode::Pending || control.end.is_some() {
            control.mode = Mode::Batch;
            return false;
        }
        control.mode = Mode::Streaming;
        true
    }

    /// Waits until there may be new audio to feed, or the recording ended.
    fn wait(&self) -> Option<End> {
        let control = self.control.lock().unwrap();
        if control.end.is_some() {
            return control.end;
        }
        let (control, _) = self.wake.wait_timeout(control, FEED_INTERVAL).unwrap();
        control.end
    }

    fn cancelled(&self) -> bool {
        self.control.lock().unwrap().end == Some(End::Cancel)
    }

    fn run(&self, jvm: &Arc<jni::JavaVM>, target_ref: &GlobalRef, audio: &Mutex<Vec<f32>>) {
        // Settings changed in the app since this process loaded the model
        // (or it is still loading): get the current one while the user speaks.
        if !engine::is_current() && engine::ensure_loaded_quietly(jvm, target_ref).is_err() {
            self.step_aside();
            return;
        }
        if !engine::engine_supports_streaming() {
            self.step_aside();
            return;
        }
        let Some(engine) = engine::get_engine() else {
            self.step_aside();
            return;
        };
        let Ok(mut env) = jvm.attach_current_thread() else {
            self.step_aside();
            return;
        };
        let obj = target_ref.as_obj();

        // Voice input goes ahead of live subtitles for the whole recording.
        let turn = InteractiveTurn::begin();
        let mut guard = engine.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        if !guard.supports_streaming() {
            self.step_aside();
            return;
        }
        let mut stream = match guard.begin_stream() {
            Ok(stream) => stream,
            Err(e) => {
                log::warn!("could not begin a stream ({}); transcribing in batch", e);
                self.step_aside();
                return;
            }
        };
        if !self.claim() {
            return;
        }
        log::info!("live transcription started");

        let mut shown = Shown::default();
        let mut fed = 0usize;
        let mut failure = None;
        'feed: loop {
            let end = self.wait();
            if end == Some(End::Cancel) {
                log::info!("live transcription cancelled");
                return;
            }
            // Everything recorded so far; after a Finish, all of it.
            let available = audio.lock().unwrap().len();
            while fed < available {
                let take = (available - fed).min(MAX_FEED_SAMPLES);
                let piece = audio.lock().unwrap()[fed..fed + take].to_vec();
                fed += take;
                match stream.feed(&piece) {
                    Ok(update) => {
                        if self.cancelled() { return; }
                        if update.committed_changed || update.tentative_changed {
                            shown.update(&mut env, obj, &stream.text());
                        }
                    }
                    Err(e) => {
                        failure = Some(e.to_string());
                        break 'feed;
                    }
                }
                if self.cancelled() {
                    log::info!("live transcription cancelled");
                    return;
                }
            }
            if end == Some(End::Finish) {
                break;
            }
        }

        let text = match failure {
            None => match stream.finalize() {
                Ok(_) => Ok(display_text(&stream.text())),
                Err(e) => Err(e.to_string()),
            },
            Some(e) => Err(e),
        };
        drop(stream);
        drop(guard);
        drop(turn);
        // A failed native feed must not complete the UI while the microphone
        // still captures. Release the engine, then wait for Stop or Cancel.
        if text.is_err() {
            while self.wait().is_none() {}
        }
        if self.cancelled() {
            return;
        }
        self.control.lock().unwrap().mode = Mode::Done;

        if text.is_ok() {
            log::info!(
                "live transcription finalized ({:.1}s audio)",
                fed as f64 / 16_000.0
            );
        }
        // Progress callbacks are provisional in every client: the keyboard
        // owns a replaceable composing region (or inserts only at the end),
        // and the popup delivers only the final result. A full retry can
        // therefore replace even a nonempty partial without duplicating it.
        let text = finalize_with_retry(text, || {
            let samples = audio.lock().unwrap().clone();
            engine::transcribe_speech(&engine, samples)
        });
        if self.cancelled() {
            return;
        }
        let text = match text {
            Ok(text) => text,
            // Preserve the recognized prefix only if BOTH streaming and
            // batch recovery failed; report the error, never silent success.
            Err(e) if !shown.is_empty() => {
                log::error!("{}", e);
                notify_status(&mut env, obj, "Ready");
                notify_text(&mut env, obj, &shown.text());
                notify_status(&mut env, obj, &format!("Error: {}", e));
                return;
            }
            Err(e) => {
                notify_status(&mut env, obj, &format!("Error: {}", e));
                return;
            }
        };
        if self.cancelled() {
            return;
        }
        notify_status(&mut env, obj, "Ready");
        notify_text(&mut env, obj, &text);
    }
}

/// Prefer a successful stream. Otherwise retry the complete recording once,
/// regardless of whether partial text was published. The caller has stopped
/// capture, released the native stream, and checked cancellation first.
fn finalize_with_retry(
    streamed: Result<String, String>,
    retry: impl FnOnce() -> Result<String, String>,
) -> Result<String, String> {
    let reason = match streamed {
        Ok(text) if !text.trim().is_empty() => return Ok(text),
        Ok(_) => "stream returned no text".to_string(),
        Err(e) => format!("stream failed: {}", e),
    };
    log::warn!("{}; retrying the full recording in batch", reason);
    retry().map_err(|e| format!("{}; batch retry failed: {}", reason, e))
}

/// The text last reported through `onStreamText`.
#[derive(Default)]
struct Shown {
    committed: String,
    tentative: String,
}

impl Shown {
    fn is_empty(&self) -> bool {
        self.committed.is_empty() && self.tentative.is_empty()
    }

    fn text(&self) -> String {
        format!("{}{}", self.committed, self.tentative)
    }

    /// Reports `text` if it differs from what is shown. The committed part
    /// only grows: should the model ever revise it, the revision is shown as
    /// tentative instead, so text already typed is never contradicted.
    fn update(&mut self, env: &mut JNIEnv, obj: &JObject, text: &transcribe_cpp::StreamText) {
        let (committed, tentative) = trim_start(&text.committed, &text.tentative);
        // Streams commit whole decoder chunks, which can end mid-word
        // ("my fellow Americ" + "ans,"). The word after the last space may
        // still grow, so it stays tentative until the stream moves past it.
        let word_start = committed
            .char_indices()
            .rev()
            .find(|(_, c)| c.is_whitespace())
            .map_or(0, |(i, c)| i + c.len_utf8());
        let tentative = format!("{}{}", &committed[word_start..], tentative);
        let committed = &committed[..word_start];
        let (committed, tentative) = if committed.starts_with(self.committed.as_str()) {
            (committed.to_string(), tentative)
        } else {
            log::warn!("stream revised its committed text; showing the revision as tentative");
            let display = format!("{}{}", committed, tentative);
            match display.strip_prefix(self.committed.as_str()) {
                Some(rest) => (self.committed.clone(), rest.to_string()),
                None => (self.committed.clone(), String::new()),
            }
        };
        if committed == self.committed && tentative == self.tentative {
            return;
        }
        self.committed = committed;
        self.tentative = tentative;
        if let (Ok(c), Ok(t)) = (env.new_string(&self.committed), env.new_string(&self.tentative)) {
            let _ = env.call_method(
                obj,
                "onStreamText",
                "(Ljava/lang/String;Ljava/lang/String;)V",
                &[(&c).into(), (&t).into()],
            );
            clear_exception(env);
            let _ = env.delete_local_ref(c);
            let _ = env.delete_local_ref(t);
        }
    }
}

/// Drops the whitespace the model puts before the first word, across the
/// committed/tentative split.
fn trim_start<'a>(committed: &'a str, tentative: &'a str) -> (&'a str, &'a str) {
    let c = committed.trim_start();
    if c.is_empty() {
        ("", tentative.trim_start())
    } else {
        (c, tentative)
    }
}

/// The final text of a finalized stream, trimmed like the reports.
fn display_text(text: &transcribe_cpp::StreamText) -> String {
    let (committed, tentative) = trim_start(&text.committed, &text.tentative);
    format!("{}{}", committed, tentative)
}

fn clear_exception(env: &mut JNIEnv) {
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_describe();
        let _ = env.exception_clear();
    }
}

fn notify_status(env: &mut JNIEnv, obj: &JObject, msg: &str) {
    if let Ok(jmsg) = env.new_string(msg) {
        let _ = env.call_method(
            obj,
            "onStatusUpdate",
            "(Ljava/lang/String;)V",
            &[(&jmsg).into()],
        );
        clear_exception(env);
    }
}

fn notify_text(env: &mut JNIEnv, obj: &JObject, text: &str) {
    if let Ok(jtxt) = env.new_string(text) {
        let _ = env.call_method(
            obj,
            "onTextTranscribed",
            "(Ljava/lang/String;)V",
            &[(&jtxt).into()],
        );
        clear_exception(env);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn live(mode: Mode) -> LiveStream {
        LiveStream {
            control: Mutex::new(Control { mode, end: None }),
            wake: Condvar::new(),
        }
    }

    #[test]
    fn stop_before_claim_uses_batch() {
        let s = live(Mode::Pending);
        assert!(!s.finish());
        assert!(!s.claim());
    }

    #[test]
    fn stop_after_claim_finalizes_once() {
        let s = live(Mode::Pending);
        assert!(s.claim());
        assert!(s.finish());
        assert_eq!(s.wait(), Some(End::Finish));
    }

    #[test]
    fn cancel_before_claim_prevents_streaming() {
        let s = live(Mode::Pending);
        s.cancel();
        assert!(!s.claim());
        assert!(s.cancelled());
    }

    #[test]
    fn cancel_overrides_pending_finalization() {
        let s = live(Mode::Streaming);
        assert!(s.finish());
        s.cancel();
        assert_eq!(s.wait(), Some(End::Cancel));
    }

    #[test]
    fn completed_worker_cannot_claim_finalization() {
        let s = live(Mode::Done);
        assert!(!s.finish());
    }

    #[test]
    fn trim_preserves_separator_and_unicode() {
        assert_eq!(trim_start("  hello ", "world"), ("hello ", "world"));
        assert_eq!(trim_start(" \t", "  héllo"), ("", "héllo"));
        assert_eq!(trim_start("héllo", " world"), ("héllo", " world"));
    }
    #[test]
    fn successful_stream_does_not_repeat_inference() {
        let text = finalize_with_retry(Ok("complete text".into()), || panic!("unexpected retry"));
        assert_eq!(text.unwrap(), "complete text");
    }

    #[test]
    fn stream_failure_after_partial_retries_full_recording() {
        let published_partial = "first sentence";
        let mut retries = 0;
        let text = finalize_with_retry(Err("feed failed after partial".into()), || {
            retries += 1;
            Ok(format!("{} plus the rest", published_partial))
        });
        assert_eq!(retries, 1);
        assert_eq!(text.unwrap(), "first sentence plus the rest");
    }

    #[test]
    fn empty_final_result_also_retries() {
        let text = finalize_with_retry(Ok("  ".into()), || Ok("recovered".into()));
        assert_eq!(text.unwrap(), "recovered");
    }

    #[test]
    fn both_failures_are_reported() {
        let error = finalize_with_retry(Err("feed failed".into()), || Err("decode failed".into()))
            .unwrap_err();
        assert!(error.contains("feed failed"));
        assert!(error.contains("batch retry failed: decode failed"));
    }

    #[test]
    fn successful_silence_retry_stays_empty() {
        assert_eq!(finalize_with_retry(Ok(String::new()), || Ok(String::new())).unwrap(), "");
    }

}
