# Native streaming for voice-input recordings

When the selected model advertises streaming support, the IME and speech popup
feed new 16 kHz audio to one transcribe.cpp session while capture continues.
Stop finalizes the remaining state and inserts/delivers one result. No partial
text is inserted into the editor or displayed in the popup in this change.
The bundled offline Parakeet model retains batch inference.

This uses the existing transcribe-cpp 0.4.0 dependency. Nemotron Speech Streaming
EN 0.6B Q4_K_M is the tested streaming model. Native model geometry, not the
100 ms pickup interval, determines decoding/update latency.

## Lifecycle and recovery

Each capture owns its buffer and a cancellation handle. Session teardown and
replacement cancel old workers. The IME rejects stale callbacks using an
immutable per-recording generation and preserves the busy state through the
final-result callback. A Ready status alone cannot re-enable Record early;
the final callback refreshes it after clearing the busy flag.

An incoming interactive inference request asks an older streaming capture to
stop before taking the shared engine. Model reset does the same before it
releases the old engine. Subtitles continue to yield to interactive inference.
Cancel cannot interrupt a native call already in progress, but suppresses its
late output.

Failed or empty streams retry the complete recording once after Stop, even
when the native decoder had already produced a prefix. Only if both attempts
fail does the app return an available partial transcript and report an error.
Successful nonempty streams do not run batch inference again.

Audio is retained for recovery, about 3.8 MB/minute of float PCM in addition to
model/cache memory. No constant-memory claim or physical-phone performance
claim is made. The native streaming path avoids the full-audio UI-thread copy
at Stop; batch-only capture transfers that work to its worker thread.

## Validation and limits

Native unit tests cover stop/cancel transitions and retry policy. With the
normal NDK environment, compile using:

    cargo ndk -t arm64-v8a test --lib --release --no-run

Run the generated executable on an arm64 emulator with the NDK libc++ runtime
on LD_LIBRARY_PATH. Android 36 recorded-audio tests exercise decoding, JNI,
ordered final insertion, repeat Record availability and cancellation.

For the forced-error replay, apply tests/native-streaming-fault-injection.patch
ONLY in a separate disposable checkout. It substitutes a readable mono 16 kHz
float32 fixture at /data/local/tmp/fake_mic.raw and forces failure after native
partial text exists. Never distribute that checkout's APK. Build/install the
model-free release flavor, import/select Nemotron, enable its IME/mic permission,
and run scripts/verify-native-streaming-emulator.py with the launcher, serial,
config and session from T3 device_open. Root ADB and Google Contacts are needed
on this disposable emulator. The driver refuses physical phones and does not
save contacts. Supply --expected-opening and --expected-tail for the fixture;
the tail word must occur after the injected failure but within six seconds.
It checks two consecutive recoveries without hiding/recreating the keyboard,
unchanged editor text during capture and a single final insertion per capture.

A user reported brief successful use of the combined sideload build on a
Pixel 7 Pro, including retention of the final sentence and no noticed bugs.
That was not a timed or isolated test of this branch. Long-duration sustained
performance, physical microphone behavior, thermals and battery use have not
been measured. Live editor composition and popup previews are separate work.
