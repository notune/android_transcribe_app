# Flow Bubble: a mic bubble that follows the keyboard

## Problem Statement

I want to dictate text into any app, the way Inspiration App works on Android.

PR #80 adds a floating mic bubble, but it does not work that way:

- The bubble shows all the time after I turn it on. It covers content in apps where I do not type.
- A persistent notification stays in the shade while the bubble is on.
- I must place the bubble by hand. It does not follow the keyboard.
- The only gesture is tap. I cannot hold to talk.
- I cannot hide the bubble for a short time.
- The accessibility service is optional and only pastes text. It does not control the bubble.

## Solution

The bubble shows only when I focus a text field and the on-screen keyboard is open. It hides when the keyboard closes.

- The bubble docks to the left or right screen edge, above the keyboard.
- I tap the bubble to start dictation, and I tap it again to stop. Or I hold the bubble to talk and release it to stop.
- While I record, a cancel button shows next to the bubble. It discards the recording.
- Recording starts at once, even when the model is not in memory. The model loads in the background.
- The app transcribes the audio on the device and inserts the text into the focused field. If insertion fails, the text stays on the clipboard.
- The model loads when I am likely to dictate, and it unloads after a period of no use.
- I drag the bubble to a target at the bottom of the screen to snooze it for 10 minutes. A shake of the phone ends the snooze.
- The bubble never shows in password, PIN, number, or phone fields. It never shows in banking apps.
- The accessibility service is required. The app guides me to turn it on.
- There is no persistent notification.

This spec is phase 1, the MVP (minimum viable product): behaviour and behaviour settings that match Inspiration App. Phase 2 adds appearance settings: size, opacity, shrink, colours and themes (see Out of Scope).

## User Stories

Setup

1. As a new user, I want the main screen to show a "Flow bubble" section with setup steps, so that I know what to turn on.
2. As a new user, I want to grant the microphone permission from that section, so that the bubble can record.
3. As a new user, I want a clear disclosure dialog before the app opens the accessibility settings, so that I know what the service reads and why.
4. As a new user, I want the section to show "Active" when the accessibility service runs, so that I know setup is complete.
5. As a user, I want the section to show "Not active" with a button to the accessibility settings when Android turns the service off, so that I can fix it fast.
6. As a user, I want a master switch "Show bubble in text fields", so that I can turn the bubble off and keep the service on.

Visibility

7. As a user, I want the bubble to show when I focus an editable text field and the keyboard is open, so that it is there when I type.
8. As a user, I want the bubble to hide when the keyboard closes, so that it does not cover content.
9. As a user, I want the bubble to show again on the next field focus, so that I do not need to turn it on each time.
10. As a user, I want the bubble to hide in password fields, so that my passwords never go through dictation.
11. As a user, I want the bubble to hide in number, PIN, phone, and date fields, so that it does not show where dictation makes no sense.
12. As a user, I want the bubble to hide in banking and finance apps, so that sensitive apps stay clean.
13. As a user, I want the bubble to hide on the lock screen, so that nobody can dictate into my locked phone.
14. As a user, I want the bubble to hide inside this app, so that it does not cover its settings.
15. As a user, I want an option to hide the bubble in search fields, so that it does not cover search bars.
15a. As a user with a physical keyboard (tablet, Bluetooth, Chromebook), I want an option to show the bubble without an on-screen keyboard, so that I can still dictate. It is off by default, like Inspiration App.

Position

16. As a user, I want the bubble to sit above the keyboard at the screen edge, so that it does not cover the field I type in.
17. As a user, I want to drag the bubble to the other edge, so that it fits how I hold the phone.
18. As a user, I want to drag the bubble up or down, so that it does not cover app buttons above the keyboard.
19. As a user, I want the app to remember the edge and the height, so that the bubble shows in the same place next time.
20. As a user, I want the bubble to move when the keyboard height changes, so that it stays above the keyboard.

Dictation

21. As a user, I want to tap the bubble to start recording, so that I can dictate hands-free.
22. As a user, I want to tap the bubble again to stop recording, so that the app transcribes what I said.
23. As a user, I want to hold the bubble to record and release it to stop, so that I can do quick push-to-talk.
24. As a user, I want a short vibration when recording starts and stops, so that I know the state without looking.
25. As a user, I want the bubble to show a recording state with a live audio level, so that I know the mic hears me.
26. As a user, I want the bubble to show a processing state, so that I know the app is transcribing.
27. As a user, I want the processing state to say "Loading model…" when the model is still loading, so that I know why there is a delay.
28. As a user, I want the app to ignore taps while it processes, so that a double tap does not start a second recording.
29. As a user, I want the bubble to stay visible while it records or processes, even if the keyboard closes, so that I can see the result.
30. As a user, I want a warning at 4 minutes of recording, so that I know the limit is near.
31. As a user, I want the recording to stop and transcribe at 5 minutes, so that one session never grows without limit.

Cancel

32. As a user, I want a cancel button next to the bubble while I record, so that I can discard a recording I do not want.
33. As a user in tap mode, I want to tap the cancel button, so that the app stops and discards the recording.
34. As a user in hold mode, I want to slide my finger onto the cancel button and release, so that I can cancel without a second hand.
35. As a user in hold mode, I want the cancel button to highlight when my finger is over it, so that I know release cancels.
36. As a user, I want a distinct vibration on cancel, so that I know nothing goes into the field.
37. As a user, I want a cancelled recording to leave no text, no clipboard change and no history entry, so that it leaves no trace.
38. As a user, I want a tap-mode recording shorter than 0.5 s to cancel itself, so that an accidental double tap does not show an error.

Insertion

39. As a user, I want the app to insert the text at the cursor of the focused field, so that I do not need to paste.
40. As a user, I want the text to stay on the clipboard when insertion fails, so that I can paste it myself.
41. As a user, I want a short toast that says "Inserted" or "Copied to clipboard", so that I know where the text went.
42. As a user, I want each transcription saved to the history, so that I can find it later.

Snooze

43. As a user, I want a drop target to show at the bottom of the screen when I drag the idle bubble, so that I know where to drop it.
44. As a user, I want to drop the bubble on the target to snooze it for 10 minutes, so that I can hide it for a while.
45. As a user, I want a vibration when the bubble enters the drop target, so that I know the drop will snooze it.
46. As a user, I want to shake the phone in a text field to end the snooze, so that I get the bubble back fast.
47. As a user, I want an "End snooze now" button in the app while the bubble is snoozed, so that I have a second way back.
48. As a user, I want the app to block snooze while it records or processes, so that I do not lose a dictation.

Model load and unload

49. As a user, I want recording to start at once when I tap, even if the model is not loaded, so that I never wait to start speaking.
50. As a user, I want the model to start loading when the bubble shows, so that it is usually ready before I stop speaking.
51. As a user, I want the app not to load the model when the bubble only flashes past, so that quick field changes do not waste processing.
52. As a user, I want the app not to load the model at boot or when the service connects, so that it uses no memory until I type.
53. As a user, I want the model to stay loaded while I keep typing and dictating, so that later dictations have no load delay.
54. As a user, I want the model to unload after the "Unload when idle" time with no use, so that it does not hold memory all day.
55. As a user, I want the model to unload when Android is low on memory, so that other apps stay fast.
56. As a user, I want the model to unload when I turn the bubble off, so that a disabled feature holds no memory.
57. As a user, I want an unload never to break a transcription in progress, so that I never lose a dictation.
58. As a user, I want no foreground service while the bubble is idle, so that the app does not drain the battery.

## Implementation Decisions

### Base branch

- Build on top of PR #80 (local branch `pr-80`). Create a new branch `feature/flow-bubble` from it.
- Keep from PR #80:
  - the Rust voice session
  - the idle unload (`engine::unload_if_idle`)
  - the bubble layout and state drawables
  - `TranscriptionHistory`
  - custom words
  - the accessibility disclosure strings
- The accessibility service keeps its class name `InsertionAccessibilityService`. Existing users do not need to turn it on again.

### Modules

- **`BubbleController`** (new, plain Java, no Android imports). It owns all bubble decisions:
  - visibility
  - the recording state machine
  - gestures
  - snooze
  - the session limit
  - position math

  It receives events and calls a `BubbleHost` interface. It is the test seam.
- **`BubbleHost`** (new interface). It is the boundary between the controller and Android. The accessibility service implements it.
- **`FieldInfo`** (new value class). It is a plain snapshot of the focused field. An adapter builds it from an `AccessibilityNodeInfo`, so the controller never touches Android types.
- **`InsertionAccessibilityService`** (changed). It becomes the only runtime host:
  - It listens to accessibility events.
  - It reads the keyboard window.
  - It builds `FieldInfo`.
  - It owns the overlay view.
  - It forwards touch events to the controller.
  - It keeps `tryInsert` and `isSafeEditable`. Field rules move into the controller.
- **`BubbleRecorder`** (new plain Java class). It holds the native methods and receives the native callbacks `onStatusUpdate(String)`, `onAudioLevel(float)` and `onTextTranscribed(String)`. It forwards them to the controller on the main thread.
- **`bubble.rs`** (changed). The JNI function names change from `BubbleService_*` to `BubbleRecorder_*`. Add `cancelRecordingNative`, which calls `voice_session::cancel_recording`. Add `isEngineLoadedNative`, which calls `engine::is_engine_loaded`. The other behaviour stays the same.
- **`BubbleService`** (deleted). The persistent foreground service and its notification go away.
- **`BubblePrefs`** (changed). See the schema below.
- **`MainActivity`** (changed). The "Floating mic bubble" section becomes "Flow bubble":
  - setup steps
  - status
  - settings
  - "End snooze now"

  The overlay-permission request goes away.
- **`bank_packages`** (new string-array resource). It is the built-in list of excluded app packages.

### Controller state machine

```
enum Mode { HIDDEN, IDLE, RECORDING_TAP, RECORDING_HOLD, PROCESSING }

HIDDEN         --showAllowed-->                        IDLE
IDLE           --showDenied-->                         HIDDEN
IDLE           --tap-->                                RECORDING_TAP
IDLE           --press held >= 350 ms-->               RECORDING_HOLD
IDLE           --drop on snooze target-->              HIDDEN (snoozeUntil = now + 10 min)
RECORDING_TAP  --tap on bubble, recorded >= 500 ms-->  PROCESSING (stopRecording)
RECORDING_TAP  --tap on bubble, recorded < 500 ms-->   IDLE (cancelRecording, no toast)
RECORDING_TAP  --tap on cancel chip-->                 IDLE (cancelRecording)
RECORDING_HOLD --release outside cancel chip-->        PROCESSING (stopRecording)
RECORDING_HOLD --release over cancel chip-->           IDLE (cancelRecording)
RECORDING_*    --5 min elapsed-->                      PROCESSING (stopRecording)
RECORDING_*    --native error-->                       IDLE (toast error)
PROCESSING     --text received-->                      IDLE or HIDDEN (re-check showAllowed)
PROCESSING     --native error-->                       IDLE (toast error)
```

- Recording starts without a wait for the model. The native `stop_recording` already waits for an in-flight load before it transcribes.
- A tap or hold in IDLE also starts a model load if the model is not loaded and no load runs.
- Taps are ignored in PROCESSING.
- In RECORDING and PROCESSING, the controller does not hide the bubble when the keyboard closes or focus changes. It re-checks visibility after the text arrives or after a cancel.
- In PROCESSING, the bubble shows a spinner. The content description is "Loading model…" while the model loads, else "Processing…".
- Cancel is not possible in PROCESSING. The native transcription cannot stop, and a late result could reach the wrong field.

### Cancel

- The cancel chip is a 36 dp circle with an "×" icon.
- It shows only in RECORDING_TAP and RECORDING_HOLD. It sits next to the bubble, on the side toward the screen centre, with a 12 dp gap.
- The controller computes the chip bounds from the bubble position. The host draws the chip.
- In RECORDING_HOLD, the chip is armed while the finger is inside its bounds plus 8 dp. An armed chip shows a highlight.
- Drag does not move the bubble in RECORDING_HOLD. Finger movement only arms or disarms the chip.
- A cancel calls the native `cancel_recording`. That function already exists in the voice session.
- A cancel gives a double short vibration. It does not show a toast.
- A cancel does not change the clipboard or the history. It does not insert text.
- A cancel re-arms the idle-unload timer.

### Visibility rule

`showAllowed` is true only when all of these are true:

- the master switch is on
- the accessibility consent is on
- the service is connected
- now is later than `snoozeUntil`
- the keyguard is not locked
- an on-screen keyboard is visible, or "show without on-screen keyboard" is on
- the focused node is editable and visible to the user
- the package is not this app, not `com.android.systemui`, and not in `bank_packages`
- the field is not a password field:
  - `isPassword()` is false
  - the input type has no password variation (text, number, visible, web)
- the input class is not number, phone, or datetime
- if "hide in search fields" is on, the field is not a search field

A search field is a node whose class name or view ID contains "search", in any case.

### Accessibility service config

- Event types: view focused, windows changed, window state changed.
- Flags: retrieve interactive windows, report view IDs.
- `canRetrieveWindowContent` stays true.
- Keyboard detection: the service reads `getWindows()`. It looks for a window of type input method. Its screen bounds give the keyboard top.
- An input-method window counts as an on-screen keyboard only when it is at least half the screen width. With a physical keyboard, Gboard shows a narrow floating toolbar (about 150 px wide on the emulator). That toolbar does not count.
- With no on-screen keyboard (option on), the bubble anchors above the navigation bar instead of the keyboard.
- Field lookup: `findFocus(FOCUS_INPUT)` returns the host View for Jetpack Compose apps (for example Google Messages). That node is not editable. The service then searches its descendants (up to 400 nodes) for a node that is focused and editable.
- After a reinstall during development, Android may not reconnect the service. Turn it off and on again (`settings put secure enabled_accessibility_services`), with a pause of a few seconds between the two.
- The overlay uses `TYPE_ACCESSIBILITY_OVERLAY`. The app no longer needs the "display over other apps" permission for the bubble.

### Position

- `x`: 8 dp from the saved edge. The default edge is right.
- `y`: `keyboardTop - bubbleSize - 16 dp - offsetAboveKeyboard`.
- The controller clamps `offsetAboveKeyboard` to keep the bubble below the status bar and above the keyboard.
- A drag that ends outside the snooze target snaps to the nearest horizontal edge. The controller saves the edge and the offset.
- Drag starts after 8 px of movement (the PR #80 value). Drag works only in IDLE.

### Snooze

- The snooze target is a circle at the bottom centre of the screen. It shows only during an IDLE drag.
- The drop counts when the bubble centre is within the target radius.
- The controller saves `snoozeUntil` as epoch milliseconds. It survives a service restart.
- Shake detection runs only while all three are true: snooze is active, a field is focused, and the keyboard is open.
- A shake is two accelerometer peaks above 2.5 g within 800 ms.
- Shake does nothing in excluded apps.

### Recording and insertion

- On a start, the host records through the Rust voice session. The call is `start_recording` with auto-stop off.
- Vibration happens on start and on stop.
- On text:
  1. Save to history with source bubble.
  2. Put the text on the clipboard.
  3. Try `ACTION_PASTE` on the focused node.
  4. Show a toast: "Inserted into text field" or "Copied to clipboard".
- The app does not restore the old clipboard. Android blocks clipboard reads from the background.
- Session limit: the app shows a toast at 4 minutes. At 5 minutes it stops and transcribes.

### Model load and unload policy

Costs, for context:

- A load reads the model file and builds the session. It costs a few seconds of CPU and disk time.
- A transcription costs CPU for about as long as it runs.
- A loaded model that waits costs memory (hundreds of MB). It costs no CPU.
- The policy therefore keeps the model loaded while use is likely. It avoids repeated loads, and it frees memory after real idle time.

Terms:

- **Warm-up**: a background model load that the controller starts before the user asks for it.
- **Use**: a bubble show, a recording start, a cancel, or the end of a transcription.
- **Idle timer**: one delayed task that fires "Unload when idle" minutes after the last use.

Load rules:

1. Do not load at boot, on service connect, or on app start. The bubble adds no memory until the user types.
2. Warm-up starts when the bubble stays visible for 400 ms without a break. A bubble that hides within 400 ms starts no load. This filters out fast field changes.
3. A tap or hold in IDLE starts a load at once if none runs and the model is not loaded. Recording does not wait for the load.
4. Only one load runs at a time. The native load state already serializes loads. The controller also tracks `loadInFlight` so that it does not ask twice.
5. The service creates the native session on connect with `voice_session::init_session_lazy`, which does not load the model. Each load (warm-up or tap) calls `loadEngineNative` on a background thread. The engine status of that load goes to a `LoadSink` (a `ContextWrapper` that only logs), so a load error never reaches the controller as a recording error. The session target is the service itself, because the engine loads the model through a `Context`.
6. A failed warm-up is silent: the log gets a line, and the bubble stays IDLE. The next tap tries again. A failure in PROCESSING shows the toast "Couldn't load the model".

Unload rules:

1. Every use re-arms the idle timer. The timer does not run in RECORDING or PROCESSING.
2. When the idle timer fires, the host calls `unloadNative` (`engine::unload_if_idle`) on a background thread.
3. If `unloadNative` returns false, another part of the app uses the engine. Re-arm the idle timer for a full interval. This is the PR #80 behaviour.
4. "Unload when idle" keeps its choices: never, 5, 15 and 30 minutes. The default stays 15.
5. The host unloads at once (through `unload_if_idle`) in these cases:
   - `onTrimMemory` with level `TRIM_MEMORY_RUNNING_LOW`, `TRIM_MEMORY_RUNNING_CRITICAL`, or `TRIM_MEMORY_BACKGROUND` and higher. `TRIM_MEMORY_UI_HIDDEN` does not count: it fires each time the user leaves the app's own screens.
   - the master switch turns off
   - the accessibility consent turns off
   - the service unbinds or is destroyed
6. Screen off does not unload directly. The idle timer keeps running.
7. An unload never interrupts a transcription. `unload_if_idle` refuses while any thread holds the engine.

Other components:

- The main app, file transcription and live subtitles share the same engine in the main process. The bubble policy applies to that shared engine.
- The voice IME runs in the `:ime` process with its own engine. This spec does not change it.
- A model, language or custom-word change reloads the engine through the existing reload path. That reload counts as a use.

Logging:

- Log each load start, load end with its duration in ms, unload, unload refusal and unload reason.
- Use the tag `FlowBubble`. The logs let us tune the 400 ms and the default idle time on real phones.

### Prefs schema (`BubblePrefs`, one file per value in `filesDir`)

| File | Type | Default | Notes |
|---|---|---|---|
| `bubble_disabled` | presence flag | absent (bubble on) | Replaces `bubble_enabled`. The default is now on. |
| `bubble_side` | int | 1 | 0 = left, 1 = right |
| `bubble_offset_y` | int, px | 0 | Offset above the keyboard |
| `bubble_snooze_until` | long, epoch ms | 0 | 0 = no snooze |
| `bubble_hide_search` | presence flag | absent (off) | |
| `bubble_no_keyboard` | presence flag | absent (off) | Show without on-screen keyboard |
| `a11y_insertion_consent` | presence flag | absent | Kept |
| `bubble_unload_minutes` | int | 15 | Kept |
| `bubble_size_dp` | int | 56 | Kept as is until phase 2 |

- Delete `bubble_x`, `bubble_y` and `bubble_enabled`, and the code that reads them.
- Follow the existing normalize-on-read pattern for `bubble_side`.

### BubbleHost contract

```java
interface BubbleHost {
    // Overlay
    void showBubble(int x, int y);
    void moveBubble(int x, int y);
    void hideBubble();
    void renderMode(Mode mode, float audioLevel, boolean modelLoading);
    void showCancelChip(boolean visible, int x, int y, boolean armed);
    void showSnoozeTarget(boolean visible, boolean armed);

    // Recording
    void startRecording();
    void stopRecording();
    void cancelRecording();

    // Engine; async results come back via onEngineLoaded / onUnloadResult
    boolean isEngineLoaded();
    void loadEngine();
    void unloadEngineIfIdle();

    // Output
    boolean insertText(String text); // clipboard + paste; true if pasted
    void saveHistory(String text);
    void vibrate(Haptic kind);       // START, STOP, CANCEL, SNOOZE_ARMED
    void toast(int stringRes);
    void setShakeListening(boolean on);

    // Environment
    int dpToPx(int dp);
    int screenWidth();
    int screenHeight();
    int statusBarHeight();
    long now();
    void postDelayed(Runnable r, long ms);
    void cancel(Runnable r);
}
```

Controller inputs:

- `onFocusSnapshot(FieldInfo, String pkg, boolean keyboardVisible, int keyboardTop, boolean locked)`
- `onTouchDown(x, y)`, `onTouchMove(x, y)`, `onTouchUp(x, y)`
- `onShake()`
- `onEngineLoaded(boolean ok)`, `onUnloadResult(boolean unloaded)`
- `onAudioLevel(float)`, `onText(String)`, `onError(String)`
- `onTrimMemory(int level)`
- `onPrefsChanged()`, `onServiceStopping()`
- `onEngineReloaded()`: an external model, language or custom-word reload

### Main screen section

The "Flow bubble" section shows, in order:

1. Microphone permission row (a button while not granted).
2. Accessibility row with status "Active" or "Not active". A button shows the disclosure dialog, then opens the settings.
3. Switch "Show bubble in text fields".
4. Switch "Hide in search fields".
4a. Switch "Show without on-screen keyboard".
5. "Snoozed until HH:MM" with the "End snooze now" button. It shows only while snoozed.
6. The existing "Unload when idle" radio group.
7. The existing size radio group, unchanged.

- Each setting change calls the controller through the running service. The service re-reads the prefs.
- Add every new string to all locales that PR #80 covers: en, de, es, fr, it, pt, ru. Non-English locales may use English text until translated.

## Testing Decisions

- **Seam:** the `BubbleHost` interface. Tests drive `BubbleController` with a fake host that records calls. The fake host controls `now()` and runs `postDelayed` tasks when the test advances time.
- Tests are plain JUnit. They do not need Robolectric, because the controller has no Android imports.
- A good test sends events in, then asserts host calls and mode. It does not read private fields.
- Test these behaviours:
  - The visibility rule: each exclusion (password, number, phone, bank, own app, locked, no keyboard, snooze, master off, search with the option on and off).
  - Show on focus and keyboard open. Hide on keyboard close. Show again on the next focus.
  - Tap start and tap stop. Hold start and release stop. The 350 ms threshold.
  - Taps ignored in PROCESSING.
  - Cancel:
    - A tap on the chip in tap mode cancels.
    - A release over the chip in hold mode cancels. A release outside it transcribes.
    - The chip arms and disarms as the finger moves in and out.
    - A tap-mode stop under 500 ms cancels without a toast.
    - Cancel never calls `insertText` or `saveHistory`.
    - The chip shows only while recording.
  - Model load and unload:
    - No load on service connect.
    - A bubble visible for 400 ms starts one load. A bubble that hides at 399 ms starts none.
    - A tap with the model unloaded calls `startRecording` at once and `loadEngine` once.
    - A second show during a load does not start a second load.
    - A warm-up failure shows no toast.
    - Each use re-arms the idle timer. No timer runs while recording or processing.
    - The timer calls `unloadEngineIfIdle`. `onUnloadResult(false)` re-arms a full interval.
    - The "never" setting arms no timer.
    - `onTrimMemory` at `TRIM_MEMORY_RUNNING_LOW`, master switch off, and `onServiceStopping` each unload at once. `TRIM_MEMORY_UI_HIDDEN` and lower levels do nothing.
  - Show without on-screen keyboard: the bubble shows above the navigation bar, still needs a safe editable field, and moves above the keyboard when one opens.
  - The bubble stays visible while recording when the keyboard closes. It hides after the text if the rule denies show.
  - Session limit: toast at 4 minutes, stop at 5 minutes.
  - Drag snaps to the nearest edge and saves the edge and offset. Drag is ignored while recording.
  - Drop on the target snoozes for 10 minutes. The bubble shows again after 10 minutes on the next focus.
  - Shake ends the snooze only when shake listening is on and the app is not excluded.
  - Insert success and failure produce the correct toast. History saves in both cases.
- `BubblePrefsTest` (Robolectric) covers the new prefs: defaults, round trip, side normalization.
- Existing examples: `BubblePrefsTest`, `CustomWordsPrefsTest` and `TranscriptionHistoryTest` show the test style.
- Run the tests with `gradlew testDebugUnitTest`. The Rust build runs first, because `preBuild` depends on `cargoNdkBuild`.
- Manual check on the x86_64 emulator (`Pixel_API35`):
  - bubble in Messages and in Chrome search
  - hidden in a password field
  - snooze and shake (use the emulator's virtual sensors)
  - cancel in tap mode and in hold mode
  - the `FlowBubble` logs: one warm-up per focus burst, and an unload after the idle time

## Out of Scope

- Phase 2 appearance settings:
  - four size presets (0.7x to 1.15x)
  - opacity (20% to 100%)
  - auto-shrink after 5 seconds idle
  - shrink to a dot
  - bubble colours for each state (idle, recording, processing)
  - themes: light, dark, follow the system, and Material You dynamic colour
  - a live preview of the bubble in settings

  Phase 1 keeps all bubble colours in drawable and colour resources, never hard-coded in Java, so that phase 2 can theme them.
- A user-editable list of excluded apps.
- Cancel in PROCESSING.
- Start and stop sounds.
- Retry of a failed transcription from saved audio.
- Clipboard restore after insertion.
- Foldable-specific layout.
- Changes to the voice IME, `VoiceRecognitionService` or live subtitles.
- Upstreaming the work to `notune/android_transcribe_app`.

## Further Notes

- **Risk to check first:** on Android 14 and later, mic capture from a background component may need a foreground service of type microphone. Build a spike before the rest of the work:
  1. Start a recording from a tap on the accessibility overlay, with no foreground service, on the API 35 emulator.
  2. If Android blocks it, start a short foreground service of type microphone on tap. Stop the service when PROCESSING ends. The notification then shows only during a dictation.

  **Result (2026-09-23, API 35 emulator):** recording works with no foreground service. The app op shows `RECORD_AUDIO: foreground`, and the audio record shows `silenced:false`. A bound accessibility service keeps the process in the foreground state. The bubble therefore uses no foreground service, and the manifest drops `FOREGROUND_SERVICE_MICROPHONE`. Check this again on a real phone and on Android 15/16 before release.
- PR #80 is open and belongs to another author (djurcola). If it changes, rebase `feature/flow-bubble` onto the new PR head.
- The `bank_packages` list starts small. Add the main Brazilian and global banks, for example Nubank, Itaú, Bradesco, Banco do Brasil, Caixa, Santander, Inter, PicPay and Mercado Pago. Verify each package name on Google Play.
- The working tree has uncommitted build tweaks: the `targetAbis` property and the Windows `libc++_shared.so` path. Commit them on their own, apart from the feature.
- Windows builds need `ANDROID_NDK_HOME`, `CMAKE_GENERATOR=Ninja` and the SDK CMake `bin` on `PATH`.
- Inspiration App references:
  - [Flow Bubble troubleshooting](https://docs.wisprflow.ai/articles/5002934560-why-is-the-wispr-bar-is-not-appearing-or-disappearing)
  - [Accessibility permission](https://docs.wisprflow.ai/articles/7669452251-accessibility-permission-on-android)
  - [Banking app detection](https://docs.wisprflow.ai/articles/4909908692-banking-app-detection-support-in-wispr-flow)
  - [Field guide, Android chapter](https://github.com/vkorost/wispr-flow-field-guide/blob/main/book/chapters/06-android.md)
