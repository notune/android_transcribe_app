# Native streaming for voice-input recordings

When a compatible selected model is available, the IME and popup feed new
16 kHz samples to a native transcribe.cpp session during capture. Stop returns
one complete result. No partial editor insertion or popup preview is part of
this backend PR. The bundled offline Parakeet model retains batch inference.
The dependency remains transcribe-cpp 0.4.0.

## Capture is independent of display status and inference scheduling

VoiceCaptureState is a UI-thread lifecycle with explicit begin, stop, complete,
fail and cancel transitions. Status strings only change display text. In
particular, "Error loading ... — using built-in model" does not discard audio,
change the generation, or disable Stop. Terminal failures use onCaptureFailed,
not an Error-prefix convention. Native success sends text before Ready.

An immutable generation in each RecordingCallbacks object rejects late events.
Duplicate begin/stop/result events cannot start a second capture or insert twice.
Only accepted starts display Listening. There are no unscoped text/level owner
callbacks that read the generation from an inference thread.

## Contention policy

The native API reserves one model compute lease for the stream lifetime.
Simply releasing the outer Rust mutex between feeds would not release it.

A competing file, benchmark, speech-service, subtitle or model-reset request
therefore defers live inference, not microphone capture. All pending/active
streams are tracked. They release native state at a feed boundary and retain
the full audio buffer. A still-recording capture then uses batch on Stop.
If Stop races that handoff, exactly one owner remains responsible for delivery.
A batch-admission counter prevents a newer stream from stealing the lease while
a competing job is queued. An older recovery can defer a newer stream's
optimization under this same policy, but cannot stop/cancel its capture.

This deliberately gives up incremental optimization under contention rather
than cutting off dictation or blocking other features for an indefinite capture.
A native call already in progress remains uninterruptible. Already-running
inference can still delay another inference; there is no parallel-model claim.

Workers do not start model loads. They can wait for the existing owner preload,
but cancel/defer prevents subsequent setup. While awaiting a busy engine they
drop model Arcs and revalidate the current model/config under its lease before
opening a stream. Reload excludes new streams until the old lease is released.
The existing init-time preload itself is not cancelled by cancelling capture.

Stream setup does not degrade or overwrite the shared language hint. Rejected
streaming request shapes retain the ordinary batch path and its existing hint
behavior.

## Recovery and delivery

A successful empty stream is valid no-speech output; it is not decoded twice.
An actual feed/finalize failure or streaming-worker panic retains the recording
and retries the full audio after Stop. Recovery is separately panic-protected.
Done is recorded only after terminal delivery, never before fallible recovery.

If recovery also fails, onCaptureFailed is sent without a partial success.
The popup returns RESULT_CANCELED, not a truncated RESULT_OK/confidence 1.0.
Recovery rejects native truncated/repetition errors rather than converting their
prefix to a successful full transcript. Existing ordinary batch best-effort
behavior is unchanged.

There are no per-feed whole-transcript snapshots. Only the final native text
is copied. The touched capture/stream/load JNI callbacks share one helper that
clears Java exceptions and releases local string references.

Audio remains buffered (~3.8 MB/minute plus model/cache memory). Error recovery
can repeat full inference after Stop. This is not constant-memory streaming.

## Tests

Host controller/concurrency tests (no JNI/model/device needed):

    rustc --edition=2021 --test src/live.rs -o /tmp/ovi-live-tests
    /tmp/ovi-live-tests

Java lifecycle tests, including the fallback warning counterexample:

    ./gradlew testFdroidReleaseUnitTest

The controller tests cover contention without capture Stop, all-worker tracking,
cancellation before setup, stop/handoff ordering, a 64-iteration concurrent
single-owner race, recovery errors/panics, and valid silence without retry.
The threaded lease test verifies that a competing job can proceed while the
capture has no Finish/Cancel request.

The Android library also builds with the normal cargo-ndk environment. The
macOS build-path adjustment is a separate PR; it is not included here.

For real-decoder replay on a disposable emulator, apply
tests/native-streaming-fault-injection.patch ONLY in an isolated checkout.
It substitutes a readable mono 16 kHz float32 fixture from
/data/local/tmp/fake_mic.raw, emits a nonfatal fallback warning and forces an
error after native partial progress. Never distribute that test APK.
scripts/verify-native-streaming-emulator.py checks two consecutive full
recoveries, a single insertion and Record availability. Pass its agent-device
launcher/serial/config/session from device_open and fixture opening/tail phrases.
The driver refuses physical phones. A current emulator replay is still needed;
the old emulator was removed. Prior replay results apply to the earlier commit,
not automatically to this revised scheduler.

Brief Pixel use of the earlier combined APK was user-reported and untimed.
This update was not installed on the connected phone. Long-duration throughput,
thermals, battery use, cold fallback/model-reload UI behavior and a complete
cross-feature device matrix remain to be validated. The live-output draft must
be rebased onto this backend revision before it is ready to merge.
