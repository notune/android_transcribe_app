# Physical-phone test checklist

Not yet run: the available Android device is an emulator, not the Pixel 7 Pro.
The automated failures use recorded audio and do not test a physical microphone.

Use the model-free production APK, import/select Nemotron Speech Streaming
EN 0.6B Q4_K_M, and confirm the installed app version. Do not use a fault-test
APK. Start with the normal inference-thread setting. Note Android version,
app version, model, inference threads and the target editor.

## Main checks

1. **Repeat capture:** in a notes field, make ten short recordings. Press Stop
   each time and wait for the final result. Record must become usable again
   without closing/reopening the keyboard. Check that each result appears once.

2. **Long capture:** dictate for 5–10 minutes in Insert at end mode, including
   a continuous stretch without pauses. End with a distinct final sentence.
   The field should stay unchanged during capture. Check the beginning,
   middle and final sentence after Stop. Measure the Stop-to-final-result wait.
   Compare a short and long run; check whether the phone gets hot or falls
   behind. No Pixel latency, battery or thermal result is claimed yet.

3. **Continuous insertion:** repeat a short and a long recording with Type
   continuously. Text should grow without repeated words or accumulating
   partial-word fragments. Check the final sentence after Stop.

4. **Editor safety:** test both a native notes field and a browser text area
   or content-editable field. Select existing text, enable continuous mode,
   and start dictation. With Record in background turned off, hide the keyboard
   to cancel. The original selection should be restored. In an editor with
   Undo, check that undone text does not reappear from late callbacks. The
   editor controls Undo grouping.

5. **Popup:** enable Auto-stop after silence. Test the popup with its streaming
   switch on and off. It should stop after the configured silence behavior,
   deliver one result, and close. Close/cancel must not deliver a later result.

6. **Lifecycle:** test keyboard hide/show with background recording enabled,
   switching fields, and changing the selected model after a recording.
   A subsequent recording must still complete. Check Parakeet too if imported;
   it should keep its batch behavior.

## If a check fails

Note the mode, target app/field, whether capture was warm or cold, approximate
recording duration, observed Stop delay and exact steps. Do not paste private
dictation or a full device log into a public issue. For a connected test phone,
collect only the relevant app logs and review them for transcript content
before sharing. A USB debugging connection would allow further local diagnosis.
