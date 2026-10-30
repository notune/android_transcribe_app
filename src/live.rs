//! Opportunistic native streaming during capture, with one final result.
//!
//! Capture is never stopped by inference contention. A competing engine
//! request defers live inference to the existing batch-on-Stop path.

#[cfg(target_os = "android")]
use once_cell::sync::Lazy;
#[cfg(target_os = "android")]
use std::sync::TryLockError;
use std::sync::{Arc, Condvar, Mutex, MutexGuard, Weak};
use std::time::Duration;

#[cfg(target_os = "android")]
use crate::engine::{self, InteractiveTurn};
#[cfg(target_os = "android")]
use crate::voice_callbacks;
#[cfg(target_os = "android")]
use jni::objects::GlobalRef;

const FEED_INTERVAL: Duration = Duration::from_millis(100);
#[cfg(target_os = "android")]
const MAX_FEED_SAMPLES: usize = 16_000;

#[derive(Clone, Copy, PartialEq, Debug)]
enum Mode {
    Pending,
    Streaming,
    Batch,
    Done,
}

#[derive(Clone, Copy, PartialEq, Debug)]
enum End {
    Finish,
    Cancel,
}

struct Control {
    mode: Mode,
    end: Option<End>,
    deferred: bool,
}

pub struct LiveStream {
    control: Mutex<Control>,
    wake: Condvar,
}

// Track all pending/live workers, not only the most recently registered one.
// No Java target is stored here and this coordinator cannot stop a microphone.
#[cfg(target_os = "android")]
static ACTIVE: Lazy<Mutex<Vec<Weak<LiveStream>>>> = Lazy::new(|| Mutex::new(Vec::new()));

fn defer_workers(active: &mut Vec<Weak<LiveStream>>) {
    active.retain(|weak| {
        if let Some(live) = weak.upgrade() {
            live.defer();
            true
        } else {
            false
        }
    });
}

/// Let another inference client run. The audio capture and buffer remain intact.
#[cfg(target_os = "android")]
pub fn defer_active() {
    defer_workers(&mut ACTIVE.lock().unwrap_or_else(|p| p.into_inner()));
}

impl LiveStream {
    fn control(&self) -> MutexGuard<'_, Control> {
        self.control.lock().unwrap_or_else(|p| p.into_inner())
    }

    #[cfg(target_os = "android")]
    pub fn start(
        jvm: Arc<jni::JavaVM>,
        target: GlobalRef,
        audio: Arc<Mutex<Vec<f32>>>,
    ) -> Arc<Self> {
        let live = Arc::new(Self {
            control: Mutex::new(Control {
                mode: Mode::Pending,
                end: None,
                deferred: false,
            }),
            wake: Condvar::new(),
        });
        {
            let mut active = ACTIVE.lock().unwrap_or_else(|p| p.into_inner());
            defer_workers(&mut active);
            active.push(Arc::downgrade(&live));
        }
        let worker = live.clone();
        std::thread::spawn(move || {
            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                worker.run(&jvm, &target, &audio)
            }));
            let result = match result {
                Ok(result) => result,
                Err(_) if worker.step_aside() => None,
                Err(_) => {
                    log::error!("stream worker panicked; retaining audio for recovery");
                    worker.wait_for_stop();
                    if worker.cancelled() {
                        None
                    } else {
                        Some(batch_safely(&jvm, &target, &audio))
                    }
                }
            };
            if !worker.cancelled() {
                if let Some(result) = result {
                    if let Ok(mut env) = jvm.attach_current_thread() {
                        match result {
                            Ok(text) => {
                                voice_callbacks::text(&mut env, target.as_obj(), &text);
                                voice_callbacks::status(&mut env, target.as_obj(), "Ready");
                            }
                            Err(error) => {
                                // Never return a truncated prefix as a successful result.
                                voice_callbacks::failure(&mut env, target.as_obj(), &error);
                            }
                        }
                    } else {
                        log::error!("cannot attach final-result callback thread");
                    }
                }
            }
            // Done means all fallible work and terminal notification are complete.
            worker.control().mode = Mode::Done;
            ACTIVE
                .lock()
                .unwrap_or_else(|p| p.into_inner())
                .retain(|weak| !weak.ptr_eq(&Arc::downgrade(&worker)));
        });
        live
    }

    /// True means this worker owns final delivery; otherwise Stop uses batch.
    pub fn finish(&self) -> bool {
        let mut c = self.control();
        if c.end == Some(End::Cancel) {
            return false;
        }
        match c.mode {
            Mode::Streaming => {
                c.end = Some(End::Finish);
                self.wake.notify_all();
                true
            }
            Mode::Pending => {
                c.mode = Mode::Batch;
                false
            }
            Mode::Batch | Mode::Done => false,
        }
    }

    pub fn cancel(&self) {
        let mut c = self.control();
        if c.mode == Mode::Pending {
            c.mode = Mode::Batch;
        }
        c.end = Some(End::Cancel);
        self.wake.notify_all();
    }

    fn defer(&self) {
        let mut c = self.control();
        // Stop ownership is retained through relinquish(), even when a
        // competing job arrives while the native remainder is being fed.
        if c.end != Some(End::Cancel) {
            c.deferred = true;
            if c.mode == Mode::Pending {
                c.mode = Mode::Batch;
            }
            self.wake.notify_all();
        }
    }

    fn preparing(&self) -> bool {
        let c = self.control();
        c.mode == Mode::Pending && c.end.is_none() && !c.deferred
    }

    fn claim(&self) -> bool {
        let mut c = self.control();
        if c.mode != Mode::Pending || c.end.is_some() || c.deferred {
            return false;
        }
        c.mode = Mode::Streaming;
        true
    }

    /// Atomically hand delivery back to Stop, unless Stop already handed it
    /// to this worker. In that race this worker performs the full batch retry.
    fn relinquish(&self) -> bool {
        let mut c = self.control();
        if c.mode == Mode::Streaming && c.end == Some(End::Finish) {
            return false;
        }
        c.mode = Mode::Batch;
        true
    }

    fn step_aside(&self) -> bool {
        let mut c = self.control();
        if c.mode == Mode::Streaming {
            false
        } else {
            c.mode = Mode::Batch;
            true
        }
    }

    fn wait(&self) -> Option<End> {
        let c = self.control();
        if c.end.is_some() {
            return c.end;
        }
        let (c, _) = self
            .wake
            .wait_timeout(c, FEED_INTERVAL)
            .unwrap_or_else(|p| p.into_inner());
        c.end
    }

    fn wait_for_stop(&self) {
        while self.wait().is_none() {}
    }

    fn cancelled(&self) -> bool {
        self.control().end == Some(End::Cancel)
    }
    fn deferred(&self) -> bool {
        self.control().deferred
    }

    #[cfg(target_os = "android")]
    fn run(
        &self,
        jvm: &Arc<jni::JavaVM>,
        target: &GlobalRef,
        audio: &Mutex<Vec<f32>>,
    ) -> Option<Result<String, String>> {
        // init_session already starts preloading. Do not start another load
        // for a cancelled capture; wait cancellably for that existing load.
        while self.preparing() && engine::is_loading() && !engine::is_current() {
            self.wait();
        }
        if !self.preparing()
            || !engine::is_current()
            || !engine::engine_supports_streaming()
            || engine::batch_requested()
        {
            self.step_aside();
            return None;
        }
        loop {
            if !self.preparing() || engine::batch_requested() {
                self.step_aside();
                return None;
            }
            let Some(engine) = engine::get_engine() else {
                self.step_aside();
                return None;
            };
            // Do not retain an old model Arc while blocked on its mutex.
            let lease = engine.try_lock();
            if matches!(&lease, Err(TryLockError::WouldBlock)) {
                drop(lease);
                drop(engine);
                self.wait();
                continue;
            }
            let mut guard = lease.unwrap_or_else(|error| match error {
                TryLockError::Poisoned(p) => p.into_inner(),
                TryLockError::WouldBlock => unreachable!(),
            });
            // Revalidate after acquiring the lease, including marker changes.
            if !self.preparing()
                || !engine::is_current_engine(&engine)
                || !guard.supports_streaming()
                || engine::batch_requested()
            {
                self.step_aside();
                return None;
            }
            let turn = InteractiveTurn::begin();
            let mut stream = match guard.begin_stream() {
                Ok(stream) => stream,
                Err(error) => {
                    log::warn!("stream setup rejected; retaining batch path: {}", error);
                    self.step_aside();
                    return None;
                }
            };
            if !self.claim() {
                return None;
            }
            log::info!("live transcription started");
            let mut fed = 0;
            let mut failure = None;
            let mut yielded = false;
            'feed: loop {
                let end = self.wait();
                if self.cancelled() {
                    return None;
                }
                if self.deferred() {
                    yielded = true;
                    break;
                }
                let available = audio.lock().unwrap_or_else(|p| p.into_inner()).len();
                while fed < available {
                    let take = (available - fed).min(MAX_FEED_SAMPLES);
                    let piece =
                        audio.lock().unwrap_or_else(|p| p.into_inner())[fed..fed + take].to_vec();
                    fed += take;
                    match stream.feed(&piece) {
                        Ok(_update) => {}
                        Err(error) => {
                            failure = Some(error.to_string());
                            break 'feed;
                        }
                    }
                    if self.cancelled() {
                        return None;
                    }
                    if self.deferred() {
                        yielded = true;
                        break 'feed;
                    }
                }
                if end == Some(End::Finish) {
                    break;
                }
            }
            let streamed = if yielded {
                Err("live inference deferred for another engine client".to_string())
            } else if let Some(error) = failure {
                Err(error)
            } else {
                stream
                    .finalize()
                    .map(|_| stream.text().full.trim().to_string())
                    .map_err(|e| e.to_string())
            };
            drop(stream);
            drop(guard);
            drop(turn);
            drop(engine);
            if yielded {
                log::info!("live inference deferred; recorded audio retained");
                if self.relinquish() {
                    return None;
                }
            }
            self.wait_for_stop();
            if self.cancelled() {
                return None;
            }
            if let Err(ref error) = streamed {
                log::warn!(
                    "stream failed: {}; retrying the full recording in batch",
                    error
                );
            }
            return Some(finalize_with_retry(streamed, || {
                batch_safely(jvm, target, audio)
            }));
        }
    }
}

/// Recovery is supervised separately too: a panic here must produce failure,
/// not strand the IME with Record disabled. Contention can defer inference,
/// but this path cannot stop or cancel another recording.
#[cfg(target_os = "android")]
fn batch_safely(
    jvm: &Arc<jni::JavaVM>,
    target: &GlobalRef,
    audio: &Mutex<Vec<f32>>,
) -> Result<String, String> {
    guard_recovery(|| {
        let samples = audio.lock().unwrap_or_else(|p| p.into_inner()).clone();
        let mut env = jvm.attach_current_thread().map_err(|e| e.to_string())?;
        if !engine::is_current() {
            engine::ensure_loaded(&mut env, target.as_obj())?;
        }
        let engine = engine::get_engine().ok_or("model not loaded")?;
        engine::transcribe_speech_recovery(&engine, samples)
    })
}

fn guard_recovery(retry: impl FnOnce() -> Result<String, String>) -> Result<String, String> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(retry))
        .unwrap_or_else(|_| Err("transcription recovery panicked; please try again".to_string()))
}

/// Empty success is valid silence, not a reason to decode the entire recording twice.
fn finalize_with_retry(
    streamed: Result<String, String>,
    retry: impl FnOnce() -> Result<String, String>,
) -> Result<String, String> {
    match streamed {
        Ok(text) => Ok(text),
        Err(error) => {
            retry().map_err(|e| format!("stream failed: {}; batch retry failed: {}", error, e))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn live(mode: Mode) -> Arc<LiveStream> {
        Arc::new(LiveStream {
            control: Mutex::new(Control {
                mode,
                end: None,
                deferred: false,
            }),
            wake: Condvar::new(),
        })
    }
    #[test]
    fn stop_before_claim_uses_batch() {
        let s = live(Mode::Pending);
        assert!(!s.finish());
        assert!(!s.claim());
    }
    #[test]
    fn claimed_worker_owns_stop() {
        let s = live(Mode::Pending);
        assert!(s.claim());
        assert!(s.finish());
        assert_eq!(s.wait(), Some(End::Finish));
    }
    #[test]
    fn cancel_prevents_setup() {
        let s = live(Mode::Pending);
        s.cancel();
        assert!(!s.preparing());
        assert!(!s.claim());
    }
    #[test]
    fn cancel_overrides_finish() {
        let s = live(Mode::Streaming);
        assert!(s.finish());
        s.cancel();
        assert!(s.cancelled());
    }
    #[test]
    fn contention_does_not_stop_capture() {
        let s = live(Mode::Streaming);
        s.defer();
        assert_eq!(s.control().end, None);
        assert!(s.relinquish());
        assert!(!s.finish());
    }
    #[test]
    fn loading_nonstreaming_capture_is_not_stopped() {
        let s = live(Mode::Pending);
        s.defer();
        assert_eq!(s.control().end, None);
        assert!(!s.preparing());
        assert!(!s.finish());
    }
    #[test]
    fn stop_wins_contention_handoff_once() {
        let s = live(Mode::Streaming);
        s.defer();
        assert!(s.finish());
        assert!(!s.relinquish());
        assert_eq!(s.control().end, Some(End::Finish));
    }
    #[test]
    fn handoff_wins_stop_once() {
        let s = live(Mode::Streaming);
        s.defer();
        assert!(s.relinquish());
        assert!(!s.finish());
    }
    #[test]
    fn registry_releases_all_workers_without_stopping_any() {
        let a = live(Mode::Streaming);
        let b = live(Mode::Pending);
        let mut registry = vec![Arc::downgrade(&a), Arc::downgrade(&b)];
        defer_workers(&mut registry);
        assert!(a.deferred());
        assert!(b.deferred());
        assert_eq!(a.control().end, None);
        assert_eq!(b.control().end, None);
    }
    #[test]
    fn no_partial_result_after_double_failure() {
        let result = finalize_with_retry(Err("feed".into()), || Err("retry".into()));
        let error = result.unwrap_err();
        assert!(error.contains("feed"));
        assert!(error.contains("retry"));
    }
    #[test]
    fn valid_silence_is_not_decoded_twice() {
        assert_eq!(
            finalize_with_retry(Ok(String::new()), || panic!("unexpected retry")).unwrap(),
            ""
        );
    }
    #[test]
    fn nonempty_success_is_not_decoded_twice() {
        assert_eq!(
            finalize_with_retry(Ok("complete".into()), || panic!("unexpected retry")).unwrap(),
            "complete"
        );
    }
    #[test]
    fn stream_error_recovers_full_recording() {
        let mut n = 0;
        assert_eq!(
            finalize_with_retry(Err("feed".into()), || {
                n += 1;
                Ok("full recording".into())
            })
            .unwrap(),
            "full recording"
        );
        assert_eq!(n, 1);
    }
    #[test]
    fn recovery_panic_returns_terminal_failure_without_releasing_delivery_early() {
        let s = live(Mode::Streaming);
        assert!(s.finish());
        let result = guard_recovery(|| panic!("retry"));
        assert!(result.unwrap_err().contains("recovery panicked"));
        assert!(!s.step_aside()); // supervisor still owns failure delivery
    }
    #[test]
    fn concurrent_stop_and_handoff_have_exactly_one_delivery_owner() {
        for _ in 0..64 {
            let s = live(Mode::Streaming);
            s.defer();
            let gate = Arc::new(std::sync::Barrier::new(3));
            let a = s.clone();
            let ga = gate.clone();
            let stop = std::thread::spawn(move || {
                ga.wait();
                a.finish()
            });
            let b = s.clone();
            let gb = gate.clone();
            let handoff = std::thread::spawn(move || {
                gb.wait();
                b.relinquish()
            });
            gate.wait();
            assert_ne!(stop.join().unwrap(), handoff.join().unwrap());
        }
    }
    #[test]
    fn waiting_for_stop_returns_after_cancel() {
        let s = live(Mode::Streaming);
        s.cancel();
        s.wait_for_stop();
        assert!(s.cancelled());
    }
    #[test]
    fn completed_worker_cannot_claim_another_result() {
        let s = live(Mode::Done);
        assert!(!s.finish());
    }
    #[test]
    fn whitespace_success_is_not_retried() {
        assert_eq!(
            finalize_with_retry(Ok(" ".into()), || panic!("unexpected retry")).unwrap(),
            " "
        );
    }

    #[test]
    fn contention_after_stop_retains_one_final_delivery_owner() {
        let s = live(Mode::Streaming);
        assert!(s.finish());
        s.defer();
        assert!(s.deferred());
        assert!(!s.relinquish());
        assert_eq!(s.control().end, Some(End::Finish));
    }
    #[test]
    fn competing_engine_job_can_run_before_capture_stops() {
        let s = live(Mode::Streaming);
        let engine = Arc::new(Mutex::new(()));
        let gate = Arc::new(std::sync::Barrier::new(2));
        let (sent, received) = std::sync::mpsc::channel();
        let worker = s.clone();
        let e = engine.clone();
        let g = gate.clone();
        let capture_worker = std::thread::spawn(move || {
            let lease = e.lock().unwrap();
            g.wait();
            while !worker.deferred() {
                worker.wait();
            }
            drop(lease);
            assert!(worker.relinquish());
        });
        gate.wait();
        let competitor = std::thread::spawn(move || {
            let _lease = engine.lock().unwrap();
            sent.send(()).unwrap();
        });
        defer_workers(&mut vec![Arc::downgrade(&s)]);
        received.recv_timeout(Duration::from_secs(2)).unwrap();
        assert_eq!(s.control().end, None); // microphone was not asked to stop
        competitor.join().unwrap();
        capture_worker.join().unwrap();
    }
}
