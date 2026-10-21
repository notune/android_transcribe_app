# Optional live text output

This builds on native streaming inference. The engine and insertion policy are
separate: choosing end-only insertion does not defer compatible-model inference
until Stop.

## Settings

- Keyboard text insertion: Insert at end (default) or Type continuously.
  The choice is read at each recording start.
- Live streaming in popup: independently controls popup streaming/preview.
  Turning it off restores the previous popup record-then-transcribe path.
  It does not hide the popup itself.
- Non-streaming models keep batch transcription in either keyboard mode.

## Editor safety

Continuous keyboard output replaces one composing region. The firm prefix is
plain; only the tentative suffix is underlined. Stop commits the complete
region, including the final native remainder or a recovered batch result.
Target editors control Undo grouping and may merge it with earlier typing.

Before replacing a nonempty selection, the IME requires a complete snapshot of
the selected text. Unavailable/incomplete snapshots fall back to final-only
insertion. Cancel restores the original selection only while the region remains
verified. On focus loss the old field is restored; the complete transcript can
follow the next focused field. User edits or selection movement end continuous
capture and invalidate later callbacks, so updates cannot overwrite those edits.

Editors without extracted-text support get keyboard preview plus final-only
insertion. Real-world editor compatibility is a remaining review/test area.

## Tests and limits

Run ./gradlew testFdroidReleaseUnitTest for nine selection-snapshot tests.
Native retry/lifecycle tests remain in src/live.rs (eleven tests).
Use the project's existing JDK/SDK/NDK and cargo-ndk build setup.

The emulator replay and isolated-checkout fault patch are documented in
tests/README.md. They deliberately simulate unavailable selected text and a
native feed failure after partial output. Test injection is not enabled by a
runtime setting and is not part of the production APK. The tests check recovery
of later speech in both modes without duplicate prefixes, preservation of
unreadable selections through capture and Cancel, and Record re-enabling.
The core-only replay can also be run with Insert at end selected.

Combined-build Android 36 arm64 tests passed with recorded input. A separate
repeat test completed two recordings per output mode without recreating the
keyboard. A user reported brief successful Pixel 7 Pro use and no noticed bugs;
this is not timed long-duration, battery, thermal, or broad browser-editor
validation. See physical-phone-tests.md for the remaining physical checks.

Source code does not change release version/signing identity or bundled models.
