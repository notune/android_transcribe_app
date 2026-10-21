# Streaming regressions

## Selection safety (ordinary JVM tests)

Run ./gradlew testFdroidReleaseUnitTest with the normal Android build environment.
LiveSelectionSnapshotTest covers unavailable and incomplete selected text,
reversed selections, UTF-16 offsets, invalid offsets and empty selections.

## Stream recovery (native Rust tests)

With the NDK environment described in docs/live-dictation-output.md, build using
cargo ndk -t arm64-v8a test --lib --release --no-run. Push the generated test
executable and NDK libc++_shared.so to an arm64 emulator; run the executable
with LD_LIBRARY_PATH set to their directory.

The suite verifies stop/cancel decisions and recovery after stream failure.
Successful nonempty streams must not run batch inference again. Failed or
empty streams retry once; a double failure retains both error messages.

## End-to-end injected failures (emulator only)

Never apply streaming-fault-injection.patch to a production checkout or ship
its APK. Use a separate disposable checkout at the same commit. Apply with:

    git apply tests/streaming-fault-injection.patch

The patch replaces capture input with /data/local/tmp/fake_mic.raw, forces a
stream failure after partial text is available, and simulates an editor that
cannot return selected text. No runtime fault switches are added to production.

Build/install that checkout's assembleFdroidRelease APK with the local debug
signing environment. Import/select Nemotron Speech Streaming EN 0.6B Q4_K_M.
Enable its voice IME and microphone permission. The test emulator needs root
ADB for app-private setting checks, plus Google Contacts for a disposable
editor. No contacts are saved. The driver refuses physical phones.

Push a known speech fixture as /data/local/tmp/fake_mic.raw (mono, 16 kHz,
float32 little-endian, at least seven seconds, readable by the app). Supply
--expected-opening and --expected-tail for that fixture: an opening phrase
spoken once, and a word spoken after the forced failure but within six seconds.
The default checks match the recorded fixture used in development.

Open the emulator with T3 device_open. Pass its exact launcher, serial, config
and session to scripts/verify-streaming-emulator.py, for example:

    python3 scripts/verify-streaming-emulator.py \
      --agent-device /absolute/path/from/device_open \
      --serial emulator-5554 --config /path/from/device_open.json \
      --session SESSION_FROM_DEVICE_OPEN \
      --expected-opening "Of course" --expected-tail college

Evidence JSON and logs go to /tmp/ovi-streaming-regressions unless overridden.
The driver tests full-recording recovery after partial output in both modes,
checks that the prefix is not duplicated, and checks that an unreadable
selection remains intact during capture and Cancel. It also checks that Record
is usable again. It restores insertion mode to Insert at end.

Reinstall the unpatched production APK when finished. These tests do not
establish real microphone behavior, battery cost, thermal behavior, or browser
editor compatibility on a phone.
