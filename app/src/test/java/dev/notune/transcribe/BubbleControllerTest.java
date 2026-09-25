package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

import dev.notune.transcribe.BubbleController.Mode;

/**
 * Drives {@link BubbleController} through a fake {@link BubbleHost}. Geometry:
 * density 1, screen 1080x2400, status bar 50, keyboard top 1600, bubble 56 px.
 * With the default right side and offset 0 the bubble sits at (1016, 1528).
 */
public class BubbleControllerTest {

    static final int KB_TOP = 1600;
    static final int BUBBLE_CX = 1016 + 28;
    static final int BUBBLE_CY = 1528 + 28;
    // Cancel chip left of the bubble: x = 1016 - 12 - 36 = 968, y = 1556 - 18.
    static final int CHIP_CX = 968 + 18;
    static final int CHIP_CY = 1538 + 18;
    // Snooze target centre: ((1080 - 64) / 2 + 32, 2400 - 48 - 64 + 32).
    static final int TARGET_CX = 540;
    static final int TARGET_CY = 2320;

    static final FieldInfo TEXT = new FieldInfo(true, true, false, 0x1, "android.widget.EditText", "com.chat:id/message");

    FakeHost host;
    BubbleController c;

    @Before
    public void setUp() {
        host = new FakeHost();
        c = new BubbleController(host, new HashSet<>(Arrays.asList("dev.notune.transcribe", "com.bank")));
    }

    void focus(FieldInfo field) {
        c.onFocusSnapshot(field, "com.chat", true, KB_TOP, false);
    }

    void focusText() {
        focus(TEXT);
    }

    void keyboardClosed() {
        c.onFocusSnapshot(TEXT, "com.chat", false, 0, false);
    }

    void tap() {
        c.onTouchDown(BUBBLE_CX, BUBBLE_CY);
        c.onTouchUp(BUBBLE_CX, BUBBLE_CY);
    }

    void startTapRecording() {
        focusText();
        tap();
        assertEquals(Mode.RECORDING_TAP, c.mode());
    }

    // --- Visibility --------------------------------------------------------------

    @Test
    public void showsOnFocusWithKeyboard() {
        focusText();
        assertEquals(Mode.IDLE, c.mode());
        assertTrue(host.has("showBubble 1016,1528,56"));
    }

    @Test
    public void hidesWhenKeyboardClosesAndShowsAgain() {
        focusText();
        keyboardClosed();
        assertEquals(Mode.HIDDEN, c.mode());
        assertTrue(host.has("hideBubble"));
        focusText();
        assertEquals(Mode.IDLE, c.mode());
    }

    @Test
    public void exclusionsKeepBubbleHidden() {
        FieldInfo password = new FieldInfo(true, true, true, 0x1, "android.widget.EditText", null);
        FieldInfo webPassword = new FieldInfo(true, true, false, 0x1 | 0xe0, "android.widget.EditText", null);
        FieldInfo number = new FieldInfo(true, true, false, 0x2, "android.widget.EditText", null);
        FieldInfo phone = new FieldInfo(true, true, false, 0x3, "android.widget.EditText", null);
        FieldInfo date = new FieldInfo(true, true, false, 0x4, "android.widget.EditText", null);
        FieldInfo notEditable = new FieldInfo(false, true, false, 0x1, "android.widget.TextView", null);
        for (FieldInfo f : new FieldInfo[]{password, webPassword, number, phone, date, notEditable, null}) {
            focus(f);
            assertEquals(Mode.HIDDEN, c.mode());
        }
        c.onFocusSnapshot(TEXT, "com.bank", true, KB_TOP, false);
        assertEquals(Mode.HIDDEN, c.mode());
        c.onFocusSnapshot(TEXT, "dev.notune.transcribe", true, KB_TOP, false);
        assertEquals(Mode.HIDDEN, c.mode());
        c.onFocusSnapshot(TEXT, "com.chat", true, KB_TOP, true); // locked
        assertEquals(Mode.HIDDEN, c.mode());
        c.onFocusSnapshot(TEXT, "com.chat", false, 0, false); // no keyboard
        assertEquals(Mode.HIDDEN, c.mode());
        assertFalse(host.has("showBubble"));
    }

    @Test
    public void ownAppShowsBubbleOnlyInTheTestField() {
        c = new BubbleController(host, new HashSet<>(Arrays.asList("dev.notune.transcribe")),
                "dev.notune.transcribe:id/bubble_test_field");
        FieldInfo other = new FieldInfo(true, true, false, 0x1, "android.widget.EditText",
                "dev.notune.transcribe:id/some_other_field");
        FieldInfo test = new FieldInfo(true, true, false, 0x1, "android.widget.EditText",
                "dev.notune.transcribe:id/bubble_test_field");
        c.onFocusSnapshot(other, "dev.notune.transcribe", true, KB_TOP, false);
        assertEquals(Mode.HIDDEN, c.mode());
        c.onFocusSnapshot(test, "dev.notune.transcribe", true, KB_TOP, false);
        assertEquals(Mode.IDLE, c.mode());
        // The same view ID in another excluded app (a bank) does not count.
        c = new BubbleController(host, new HashSet<>(Arrays.asList("com.bank")),
                "dev.notune.transcribe:id/bubble_test_field");
        c.onFocusSnapshot(test, "com.bank", true, KB_TOP, false);
        assertEquals(Mode.HIDDEN, c.mode());
    }

    @Test
    public void masterSwitchAndConsentGateTheBubble() {
        host.enabled = false;
        c.onPrefsChanged();
        focusText();
        assertEquals(Mode.HIDDEN, c.mode());
        host.enabled = true;
        host.consent = false;
        c.onPrefsChanged();
        assertEquals(Mode.HIDDEN, c.mode());
        host.consent = true;
        c.onPrefsChanged();
        assertEquals(Mode.IDLE, c.mode());
    }

    @Test
    public void searchFieldsHiddenOnlyWithOption() {
        FieldInfo search = new FieldInfo(true, true, false, 0x1, "android.widget.EditText", "com.app:id/search_src_text");
        focus(search);
        assertEquals(Mode.IDLE, c.mode());
        host.hideInSearch = true;
        c.onPrefsChanged();
        assertEquals(Mode.HIDDEN, c.mode());
    }

    @Test
    public void noKeyboardOptionShowsAboveNavigationBar() {
        keyboardClosed();
        assertEquals(Mode.HIDDEN, c.mode());
        host.showWithoutKeyboard = true;
        c.onPrefsChanged();
        assertEquals(Mode.IDLE, c.mode());
        // 2400 - 100 (nav bar) - 56 - 16 = 2228
        assertTrue(host.has("showBubble 1016,2228,56"));
    }

    @Test
    public void noKeyboardOptionStillNeedsAnEditableField() {
        host.showWithoutKeyboard = true;
        c.onPrefsChanged();
        c.onFocusSnapshot(null, "com.chat", false, 0, false);
        assertEquals(Mode.HIDDEN, c.mode());
        FieldInfo password = new FieldInfo(true, true, true, 0x1, "android.widget.EditText", null);
        c.onFocusSnapshot(password, "com.chat", false, 0, false);
        assertEquals(Mode.HIDDEN, c.mode());
    }

    @Test
    public void noKeyboardOptionMovesUpWhenKeyboardOpens() {
        host.showWithoutKeyboard = true;
        c.onPrefsChanged();
        keyboardClosed();
        focusText();
        assertTrue(host.has("moveBubble 1016,1528"));
    }

    @Test
    public void followsKeyboardHeight() {
        focusText();
        c.onFocusSnapshot(TEXT, "com.chat", true, 1400, false);
        assertTrue(host.has("moveBubble 1016,1328 animated"));
    }

    // --- Recording ----------------------------------------------------------------

    @Test
    public void tapStartsAndTapStops() {
        startTapRecording();
        assertTrue(host.has("startRecording"));
        assertTrue(host.has("vibrate START"));
        host.advance(1000);
        tap();
        assertEquals(Mode.PROCESSING, c.mode());
        assertTrue(host.has("stopRecording"));
        c.onText("hello");
        assertEquals(Mode.IDLE, c.mode());
    }

    @Test
    public void holdRecordsUntilRelease() {
        focusText();
        c.onTouchDown(BUBBLE_CX, BUBBLE_CY);
        host.advance(BubbleController.HOLD_MS - 1);
        assertEquals(Mode.IDLE, c.mode());
        host.advance(1);
        assertEquals(Mode.RECORDING_HOLD, c.mode());
        host.advance(2000);
        c.onTouchUp(BUBBLE_CX, BUBBLE_CY);
        assertEquals(Mode.PROCESSING, c.mode());
    }

    @Test
    public void tapsIgnoredWhileProcessing() {
        startTapRecording();
        host.advance(1000);
        tap();
        host.calls.clear();
        tap();
        assertEquals(Mode.PROCESSING, c.mode());
        assertFalse(host.has("startRecording"));
    }

    @Test
    public void staysVisibleWhileRecordingThenHidesAfterText() {
        startTapRecording();
        keyboardClosed();
        assertEquals(Mode.RECORDING_TAP, c.mode());
        host.advance(1000);
        tap();
        keyboardClosed();
        assertEquals(Mode.PROCESSING, c.mode());
        c.onText("hi");
        assertEquals(Mode.HIDDEN, c.mode());
    }

    @Test
    public void sessionLimitWarnsThenStops() {
        startTapRecording();
        host.advance(BubbleController.LIMIT_WARNING_MS);
        assertTrue(host.has("toast LIMIT_WARNING"));
        assertEquals(Mode.RECORDING_TAP, c.mode());
        host.advance(BubbleController.LIMIT_MS - BubbleController.LIMIT_WARNING_MS);
        assertEquals(Mode.PROCESSING, c.mode());
        assertTrue(host.has("stopRecording"));
    }

    @Test
    public void recordingErrorReturnsToIdleWithToast() {
        startTapRecording();
        c.onError("Error: failed to open microphone");
        assertEquals(Mode.IDLE, c.mode());
        assertTrue(host.has("toastText Error: failed to open microphone"));
        assertTrue(host.has("chip hidden"));
    }

    // --- Insertion ------------------------------------------------------------------

    @Test
    public void insertedTextSavesHistoryAndToasts() {
        startTapRecording();
        host.advance(1000);
        tap();
        c.onText("hello");
        assertTrue(host.has("saveHistory hello"));
        assertTrue(host.has("insertText hello"));
        assertFalse(host.has("toast")); // success is silent
    }

    @Test
    public void failedInsertLeavesClipboardToast() {
        host.insertOk = false;
        startTapRecording();
        host.advance(1000);
        tap();
        c.onText("hello");
        assertTrue(host.has("saveHistory hello"));
        assertTrue(host.has("toast COPIED"));
    }

    // --- Cancel ---------------------------------------------------------------------

    @Test
    public void chipShowsOnlyWhileRecording() {
        focusText();
        assertFalse(host.has("chip visible"));
        tap();
        assertTrue(host.has("chip visible 968,1538"));
        host.advance(1000);
        tap();
        assertTrue(host.has("chip hidden"));
    }

    @Test
    public void chipTapCancelsWithoutOutput() {
        startTapRecording();
        host.advance(1000);
        c.onCancelChipTap();
        assertEquals(Mode.IDLE, c.mode());
        assertTrue(host.has("cancelRecording"));
        assertTrue(host.has("vibrate CANCEL"));
        assertFalse(host.has("stopRecording"));
        assertFalse(host.has("insertText"));
        assertFalse(host.has("saveHistory"));
        assertFalse(host.has("toast"));
    }

    @Test
    public void holdReleaseOverChipCancels() {
        focusText();
        c.onTouchDown(BUBBLE_CX, BUBBLE_CY);
        host.advance(BubbleController.HOLD_MS);
        c.onTouchMove(CHIP_CX, CHIP_CY);
        assertTrue(host.has("chip visible 968,1538 armed"));
        c.onTouchUp(CHIP_CX, CHIP_CY);
        assertEquals(Mode.IDLE, c.mode());
        assertTrue(host.has("cancelRecording"));
        assertFalse(host.has("stopRecording"));
    }

    @Test
    public void holdChipDisarmsWhenFingerLeaves() {
        focusText();
        c.onTouchDown(BUBBLE_CX, BUBBLE_CY);
        host.advance(BubbleController.HOLD_MS);
        c.onTouchMove(CHIP_CX, CHIP_CY);
        c.onTouchMove(BUBBLE_CX, BUBBLE_CY);
        assertTrue(host.last("chip").endsWith("968,1538"));
        c.onTouchUp(BUBBLE_CX, BUBBLE_CY);
        assertEquals(Mode.PROCESSING, c.mode());
    }

    @Test
    public void shortTapRecordingCancelsSilently() {
        startTapRecording();
        host.advance(BubbleController.SHORT_RECORDING_MS - 1);
        tap();
        assertEquals(Mode.IDLE, c.mode());
        assertTrue(host.has("cancelRecording"));
        assertFalse(host.has("toast"));
    }

    // --- Drag and snooze -------------------------------------------------------------

    @Test
    public void dragSnapsToNearestEdgeAndSaves() {
        focusText();
        c.onTouchDown(BUBBLE_CX, BUBBLE_CY);
        c.onTouchMove(200, BUBBLE_CY - 100);
        c.onTouchUp(200, BUBBLE_CY - 100);
        assertEquals(Mode.IDLE, c.mode());
        assertEquals(BubbleController.SIDE_LEFT, host.side);
        assertEquals(100, host.offsetY);
        assertEquals("moveBubble 8,1428 animated", host.last("moveBubble"));
        assertFalse(host.has("startRecording"));
    }

    @Test
    public void dragIgnoredWhileRecording() {
        startTapRecording();
        host.calls.clear();
        c.onTouchDown(BUBBLE_CX, BUBBLE_CY);
        c.onTouchMove(200, 400);
        assertFalse(host.has("moveBubble"));
        assertFalse(host.has("snoozeTarget"));
    }

    @Test
    public void dropOnTargetSnoozesTenMinutes() {
        focusText();
        dropOnTarget();
        assertEquals(Mode.HIDDEN, c.mode());
        assertEquals(host.now + BubbleController.SNOOZE_MS, host.snoozeUntil);
        assertTrue(host.has("vibrate SNOOZE_ARMED"));
        focusText();
        assertEquals(Mode.HIDDEN, c.mode());
        host.advance(BubbleController.SNOOZE_MS);
        assertEquals(Mode.IDLE, c.mode());
    }

    @Test
    public void shakeEndsSnoozeOnlyWhenListening() {
        focusText();
        dropOnTarget();
        assertTrue(host.shakeListening);
        c.onShake();
        assertEquals(Mode.IDLE, c.mode());
        assertEquals(0L, host.snoozeUntil);
        assertFalse(host.shakeListening);
    }

    @Test
    public void shakeIgnoredInExcludedApp() {
        focusText();
        dropOnTarget();
        c.onFocusSnapshot(TEXT, "com.bank", true, KB_TOP, false);
        assertFalse(host.shakeListening);
        c.onShake();
        assertTrue(host.snoozeUntil > 0);
    }

    void dropOnTarget() {
        c.onTouchDown(BUBBLE_CX, BUBBLE_CY);
        c.onTouchMove(TARGET_CX, TARGET_CY);
        c.onTouchUp(TARGET_CX, TARGET_CY);
    }

    // --- Model load and unload --------------------------------------------------------

    @Test
    public void noLoadBeforeAnyFocus() {
        host.advance(60_000);
        assertEquals(0, host.count("loadEngine"));
    }

    @Test
    public void warmUpAfterBubbleVisibleFor400ms() {
        focusText();
        host.advance(BubbleController.WARM_UP_DELAY_MS - 1);
        assertEquals(0, host.count("loadEngine"));
        host.advance(1);
        assertEquals(1, host.count("loadEngine"));
    }

    @Test
    public void briefFocusStartsNoLoad() {
        focusText();
        host.advance(BubbleController.WARM_UP_DELAY_MS - 1);
        keyboardClosed();
        host.advance(10_000);
        assertEquals(0, host.count("loadEngine"));
    }

    @Test
    public void tapWithModelUnloadedRecordsAtOnceAndLoadsOnce() {
        focusText();
        tap();
        assertTrue(host.has("startRecording"));
        assertEquals(1, host.count("loadEngine"));
        host.advance(1000);
        assertEquals(1, host.count("loadEngine"));
    }

    @Test
    public void secondShowDuringLoadStartsNoSecondLoad() {
        focusText();
        host.advance(BubbleController.WARM_UP_DELAY_MS);
        keyboardClosed();
        focusText();
        host.advance(BubbleController.WARM_UP_DELAY_MS);
        assertEquals(1, host.count("loadEngine"));
    }

    @Test
    public void warmUpFailureIsSilent() {
        focusText();
        host.advance(BubbleController.WARM_UP_DELAY_MS);
        c.onEngineLoaded(false);
        c.onError("Error: model file not found");
        assertFalse(host.has("toast"));
        assertEquals(Mode.IDLE, c.mode());
    }

    @Test
    public void processingShowsLoadingWhileModelLoads() {
        startTapRecording();
        host.advance(1000);
        tap();
        assertTrue(host.has("render PROCESSING loading"));
        host.engineLoaded = true;
        c.onEngineLoaded(true);
        assertTrue(host.last("render").equals("render PROCESSING"));
    }

    @Test
    public void idleTimerUnloadsAfterLastUse() {
        host.engineLoaded = true;
        focusText();
        host.advance(15 * 60_000L - 1);
        assertEquals(0, host.count("unloadEngineIfIdle"));
        host.advance(1);
        assertEquals(1, host.count("unloadEngineIfIdle"));
    }

    @Test
    public void cancelIsAUseThatRearmsIdleTimer() {
        host.engineLoaded = true;
        focusText();
        host.advance(10 * 60_000L);
        tap();
        host.advance(1000);
        c.onCancelChipTap();
        host.advance(15 * 60_000L - 1);
        assertEquals(0, host.count("unloadEngineIfIdle"));
        host.advance(1);
        assertEquals(1, host.count("unloadEngineIfIdle"));
    }

    @Test
    public void noIdleTimerWhileBusyAndTextRearms() {
        host.engineLoaded = true;
        focusText();
        tap(); // recording cancels the timer
        host.advance(20 * 60_000L); // auto-stops at 5 min, then stays PROCESSING
        assertEquals(Mode.PROCESSING, c.mode());
        assertEquals(0, host.count("unloadEngineIfIdle"));
        c.onText("done");
        host.advance(15 * 60_000L);
        assertEquals(1, host.count("unloadEngineIfIdle"));
    }

    @Test
    public void refusedUnloadRearmsFullInterval() {
        host.engineLoaded = true;
        focusText();
        host.advance(15 * 60_000L);
        c.onUnloadResult(false);
        host.advance(15 * 60_000L);
        assertEquals(2, host.count("unloadEngineIfIdle"));
    }

    @Test
    public void neverSettingArmsNoTimer() {
        host.engineLoaded = true;
        host.unloadMinutes = 0;
        c.onPrefsChanged();
        focusText();
        host.advance(120 * 60_000L);
        assertEquals(0, host.count("unloadEngineIfIdle"));
    }

    @Test
    public void lowMemoryUnloadsAtOnceButUiHiddenDoesNot() {
        host.engineLoaded = true;
        focusText();
        c.onTrimMemory(20); // TRIM_MEMORY_UI_HIDDEN
        c.onTrimMemory(5);  // TRIM_MEMORY_RUNNING_MODERATE
        assertEquals(0, host.count("unloadEngineIfIdle"));
        c.onTrimMemory(BubbleController.TRIM_MEMORY_RUNNING_LOW);
        assertEquals(1, host.count("unloadEngineIfIdle"));
    }

    @Test
    public void masterSwitchOffUnloadsAtOnce() {
        host.engineLoaded = true;
        focusText();
        host.enabled = false;
        c.onPrefsChanged();
        assertEquals(1, host.count("unloadEngineIfIdle"));
        assertEquals(Mode.HIDDEN, c.mode());
    }

    @Test
    public void serviceStoppingCancelsRecordingAndUnloads() {
        host.engineLoaded = true;
        startTapRecording();
        c.onServiceStopping();
        assertTrue(host.has("cancelRecording"));
        assertEquals(1, host.count("unloadEngineIfIdle"));
        assertEquals(Mode.HIDDEN, c.mode());
    }

    // --- Auto-shrink ------------------------------------------------------------------

    void enableShrink() {
        host.autoShrink = true;
        c.onPrefsChanged();
    }

    @Test
    public void shrinksAfterFiveSecondsIdle() {
        enableShrink();
        focusText();
        host.advance(BubbleController.SHRINK_DELAY_MS - 1);
        assertFalse(host.shrunk);
        host.advance(1);
        assertTrue(host.shrunk);
    }

    @Test
    public void noShrinkWhenOptionOff() {
        focusText();
        host.advance(60_000);
        assertFalse(host.has("shrunk"));
    }

    @Test
    public void noShrinkWhileRecording() {
        enableShrink();
        startTapRecording();
        host.advance(60_000);
        assertFalse(host.shrunk);
    }

    @Test
    public void touchOnShrunkBubbleRestoresWithoutRecording() {
        enableShrink();
        focusText();
        host.advance(BubbleController.SHRINK_DELAY_MS);
        assertTrue(host.shrunk);
        host.calls.clear();
        c.onTouchDown(BUBBLE_CX, BUBBLE_CY);
        host.advance(BubbleController.HOLD_MS + 100); // no hold either
        c.onTouchUp(BUBBLE_CX, BUBBLE_CY);
        assertFalse(host.shrunk);
        assertFalse(host.has("startRecording"));
        assertEquals(Mode.IDLE, c.mode());
        tap(); // the next gesture works as normal
        assertEquals(Mode.RECORDING_TAP, c.mode());
    }

    @Test
    public void shrinkTimerRestartsAfterTextAndTouch() {
        enableShrink();
        startTapRecording();
        host.advance(1000);
        tap();
        c.onText("hi");
        host.advance(BubbleController.SHRINK_DELAY_MS - 1);
        assertFalse(host.shrunk);
        host.advance(1);
        assertTrue(host.shrunk);
    }

    @Test
    public void turningShrinkOffRestores() {
        enableShrink();
        focusText();
        host.advance(BubbleController.SHRINK_DELAY_MS);
        host.autoShrink = false;
        c.onPrefsChanged();
        assertFalse(host.shrunk);
    }

    @Test
    public void showStartsUnshrunk() {
        enableShrink();
        focusText();
        host.advance(BubbleController.SHRINK_DELAY_MS);
        keyboardClosed();
        focusText();
        host.advance(BubbleController.SHRINK_DELAY_MS - 1);
        assertEquals(1, host.count("shrunk true"));
    }

    // --- Fake host ---------------------------------------------------------------------

    static final class FakeHost implements BubbleHost {
        final List<String> calls = new ArrayList<>();
        final List<Object[]> tasks = new ArrayList<>(); // {dueAt, runnable}
        long now = 1_000_000L;

        boolean enabled = true, consent = true, hideInSearch = false, showWithoutKeyboard = false;
        int unloadMinutes = 15, scaleIndex = 2, side = BubbleController.SIDE_RIGHT, offsetY = 0;
        boolean autoShrink = false;
        boolean shrunk = false;
        long snoozeUntil = 0;
        boolean engineLoaded = false;
        boolean insertOk = true;
        boolean shakeListening = false;

        boolean has(String prefix) {
            for (String s : calls) if (s.startsWith(prefix)) return true;
            return false;
        }

        int count(String prefix) {
            int n = 0;
            for (String s : calls) if (s.startsWith(prefix)) n++;
            return n;
        }

        String last(String prefix) {
            List<String> rev = new ArrayList<>(calls);
            Collections.reverse(rev);
            for (String s : rev) if (s.startsWith(prefix)) return s;
            return "";
        }

        /** Advances the clock and runs due tasks in time order. */
        void advance(long ms) {
            long target = now + ms;
            while (true) {
                Object[] next = null;
                for (Object[] t : tasks) {
                    if ((long) t[0] <= target && (next == null || (long) t[0] < (long) next[0])) next = t;
                }
                if (next == null) break;
                tasks.remove(next);
                now = (long) next[0];
                ((Runnable) next[1]).run();
            }
            now = target;
        }

        @Override public void showBubble(int x, int y, int sizePx) { calls.add("showBubble " + x + "," + y + "," + sizePx); }
        @Override public void moveBubble(int x, int y, boolean animate) {
            calls.add("moveBubble " + x + "," + y + (animate ? " animated" : ""));
        }
        @Override public void hideBubble() { calls.add("hideBubble"); }
        @Override public void renderMode(Mode mode, float level, boolean loading) {
            calls.add("render " + mode + (loading ? " loading" : ""));
        }
        @Override public void showCancelChip(boolean visible, int x, int y, int sizePx, boolean armed) {
            calls.add(visible ? "chip visible " + x + "," + y + (armed ? " armed" : "") : "chip hidden");
        }
        @Override public void showSnoozeTarget(boolean visible, int x, int y, int sizePx, boolean armed) {
            calls.add("snoozeTarget " + visible + (armed ? " armed" : ""));
        }
        @Override public void setShrunk(boolean on) { shrunk = on; calls.add("shrunk " + on); }
        @Override public void startRecording() { calls.add("startRecording"); }
        @Override public void stopRecording() { calls.add("stopRecording"); }
        @Override public void cancelRecording() { calls.add("cancelRecording"); }
        @Override public boolean isEngineLoaded() { return engineLoaded; }
        @Override public void loadEngine() { calls.add("loadEngine"); }
        @Override public void unloadEngineIfIdle() { calls.add("unloadEngineIfIdle"); }
        @Override public boolean insertText(String text) { calls.add("insertText " + text); return insertOk; }
        @Override public void saveHistory(String text) { calls.add("saveHistory " + text); }
        @Override public void vibrate(Haptic kind) { calls.add("vibrate " + kind); }
        @Override public void toast(Message message) { calls.add("toast " + message); }
        @Override public void toastText(String text) { calls.add("toastText " + text); }
        @Override public void setShakeListening(boolean on) { shakeListening = on; calls.add("shake " + on); }
        @Override public Settings settings() {
            return new Settings(enabled, consent, hideInSearch, showWithoutKeyboard,
                    unloadMinutes, side, offsetY, snoozeUntil, 0, scaleIndex, 100,
                    autoShrink, false);
        }
        @Override public void saveSide(int side) { this.side = side; }
        @Override public void saveOffsetY(int offsetY) { this.offsetY = offsetY; }
        @Override public void saveSnoozeUntil(long epochMs) { snoozeUntil = epochMs; }
        @Override public int dpToPx(int dp) { return dp; }
        @Override public int screenWidth() { return 1080; }
        @Override public int screenHeight() { return 2400; }
        @Override public int statusBarHeight() { return 50; }
        @Override public int navigationBarHeight() { return 100; }
        @Override public long now() { return now; }
        @Override public void postDelayed(Runnable r, long ms) { tasks.add(new Object[]{now + ms, r}); }
        @Override public void cancel(Runnable r) {
            for (Iterator<Object[]> it = tasks.iterator(); it.hasNext(); ) {
                if (it.next()[1] == r) it.remove();
            }
        }
        @Override public void log(String message) { }
    }
}
