package dev.notune.transcribe;

import java.util.Set;

/**
 * All Flow bubble decisions: when the bubble shows, the recording state
 * machine, gestures, cancel, snooze, the session limit, position math and the
 * model load/unload policy. It has no Android imports; it receives events and
 * acts through {@link BubbleHost}. See docs/specs/flow-bubble.md.
 */
public final class BubbleController {

    public enum Mode { HIDDEN, IDLE, RECORDING_TAP, RECORDING_HOLD, PROCESSING }

    public static final int SIDE_LEFT = 0;
    public static final int SIDE_RIGHT = 1;

    static final long HOLD_MS = 350;
    static final long SHORT_RECORDING_MS = 500;
    static final long WARM_UP_DELAY_MS = 400;
    static final long SNOOZE_MS = 10 * 60_000L;
    static final long LIMIT_WARNING_MS = 4 * 60_000L;
    static final long LIMIT_MS = 5 * 60_000L;
    static final long SHRINK_DELAY_MS = 5000;
    static final int TOUCH_SLOP_PX = 8;
    static final int EDGE_MARGIN_DP = 8;
    static final int KEYBOARD_GAP_DP = 16;
    static final int CHIP_DP = 36;
    static final int CHIP_GAP_DP = 12;
    static final int CHIP_ARM_MARGIN_DP = 8;
    static final int TARGET_DP = 64;
    static final int TARGET_BOTTOM_MARGIN_DP = 48;

    // ComponentCallbacks2 trim levels, inlined.
    static final int TRIM_MEMORY_RUNNING_LOW = 10;
    static final int TRIM_MEMORY_RUNNING_CRITICAL = 15;
    static final int TRIM_MEMORY_BACKGROUND = 40;

    private final BubbleHost host;
    private final Set<String> excludedPackages;
    private final String testFieldId;

    private Mode mode = Mode.HIDDEN;
    private BubbleHost.Settings settings;

    // Latest focus snapshot.
    private FieldInfo field;
    private String pkg;
    private boolean keyboardVisible;
    private int keyboardTop;
    private boolean locked;

    // Bubble geometry (px).
    private int bubbleX, bubbleY, sizePx;

    // Touch tracking.
    private boolean fingerDown;
    private boolean dragging;
    private int downX, downY, downBubbleX, downBubbleY;
    private boolean snoozeArmed;
    private boolean chipArmed;

    private long recordingStartedAt;
    private boolean loadInFlight;
    private boolean shakeListening;
    private boolean shrunk;
    /** The gesture that restored a shrunk bubble; its move/up are ignored. */
    private boolean swallowGesture;

    private final Runnable holdTask = this::onHoldElapsed;
    private final Runnable warmUpTask = this::onWarmUpElapsed;
    private final Runnable idleTask = this::onIdleElapsed;
    private final Runnable limitWarningTask = this::onLimitWarning;
    private final Runnable limitTask = this::onLimitReached;
    private final Runnable snoozeEndTask = this::evaluate;
    private final Runnable shrinkTask = this::onShrinkElapsed;

    public BubbleController(BubbleHost host, Set<String> excludedPackages) {
        this(host, excludedPackages, null);
    }

    /**
     * @param testFieldId full view ID ("pkg:id/name") of a field that shows the
     *                    bubble even inside an excluded package: the app's own
     *                    "Try the bubble" field
     */
    public BubbleController(BubbleHost host, Set<String> excludedPackages, String testFieldId) {
        this.host = host;
        this.excludedPackages = excludedPackages;
        this.testFieldId = testFieldId;
        this.settings = host.settings();
    }

    public Mode mode() {
        return mode;
    }

    // --- Inputs --------------------------------------------------------------

    public void onFocusSnapshot(FieldInfo field, String pkg, boolean keyboardVisible,
                                int keyboardTop, boolean locked) {
        this.field = field;
        this.pkg = pkg;
        this.keyboardVisible = keyboardVisible;
        this.keyboardTop = keyboardTop;
        this.locked = locked;
        evaluate();
    }

    public void onTouchDown(int x, int y) {
        if (mode == Mode.HIDDEN) return;
        host.cancel(shrinkTask);
        if (shrunk) {
            // First touch only restores the bubble; it never records.
            restore();
            swallowGesture = true;
            return;
        }
        fingerDown = true;
        dragging = false;
        downX = x;
        downY = y;
        downBubbleX = bubbleX;
        downBubbleY = bubbleY;
        if (mode == Mode.IDLE) {
            host.postDelayed(holdTask, HOLD_MS);
        }
    }

    public void onTouchMove(int x, int y) {
        if (swallowGesture || !fingerDown) return;
        int dx = x - downX;
        int dy = y - downY;
        if (mode == Mode.IDLE) {
            if (!dragging && (Math.abs(dx) > TOUCH_SLOP_PX || Math.abs(dy) > TOUCH_SLOP_PX)) {
                dragging = true;
                host.cancel(holdTask);
                snoozeArmed = false;
                showSnoozeTarget(true);
            }
            if (dragging) {
                bubbleX = downBubbleX + dx;
                bubbleY = downBubbleY + dy;
                host.moveBubble(bubbleX, bubbleY, false);
                boolean armed = bubbleCentreInTarget();
                if (armed != snoozeArmed) {
                    snoozeArmed = armed;
                    if (armed) host.vibrate(BubbleHost.Haptic.SNOOZE_ARMED);
                    showSnoozeTarget(true);
                }
            }
        } else if (mode == Mode.RECORDING_HOLD) {
            boolean armed = pointInChip(x, y, host.dpToPx(CHIP_ARM_MARGIN_DP));
            if (armed != chipArmed) {
                chipArmed = armed;
                showChip(true);
            }
        }
    }

    public void onTouchUp(int x, int y) {
        if (swallowGesture) {
            swallowGesture = false;
            armShrinkTimer();
            return;
        }
        if (!fingerDown) return;
        fingerDown = false;
        host.cancel(holdTask);
        switch (mode) {
            case IDLE:
                if (dragging) {
                    dragging = false;
                    showSnoozeTarget(false);
                    if (snoozeArmed) {
                        snoozeArmed = false;
                        snooze();
                    } else {
                        snapToEdge();
                        armShrinkTimer();
                    }
                } else {
                    startRecording(Mode.RECORDING_TAP);
                }
                break;
            case RECORDING_TAP:
                if (host.now() - recordingStartedAt < SHORT_RECORDING_MS) {
                    cancelRecording();
                } else {
                    stopRecording();
                }
                break;
            case RECORDING_HOLD:
                if (chipArmed) {
                    cancelRecording();
                } else {
                    stopRecording();
                }
                break;
            default:
                break;
        }
    }

    /** A tap on the cancel chip (tap mode). */
    public void onCancelChipTap() {
        if (mode == Mode.RECORDING_TAP || mode == Mode.RECORDING_HOLD) {
            cancelRecording();
        }
    }

    public void onShake() {
        if (!shakeListening) return;
        host.log("shake: snooze ended");
        host.saveSnoozeUntil(0);
        settings = host.settings();
        host.cancel(snoozeEndTask);
        evaluate();
    }

    public void onEngineLoaded(boolean ok) {
        loadInFlight = false;
        host.log(ok ? "load done" : "load failed");
        if (mode == Mode.PROCESSING) render();
    }

    public void onUnloadResult(boolean unloaded) {
        if (unloaded) {
            host.log("unloaded after idle");
        } else {
            host.log("unload refused: engine in use");
            armIdleTimer();
        }
    }

    public void onAudioLevel(float level) {
        if (isRecording()) host.renderMode(mode, level, false);
    }

    public void onText(String text) {
        if (mode != Mode.PROCESSING) return;
        mode = Mode.IDLE;
        if (text != null && !text.trim().isEmpty()) {
            host.saveHistory(text);
            // Success needs no toast: the text shows up in the field. Tell the
            // user only when it fell back to the clipboard.
            if (!host.insertText(text)) host.toast(BubbleHost.Message.COPIED);
        }
        afterSession();
    }

    public void onError(String message) {
        if (isRecording()) {
            clearRecordingUi();
        } else if (mode != Mode.PROCESSING) {
            // A warm-up or other background error: stay silent.
            host.log("ignored error: " + message);
            return;
        }
        mode = Mode.IDLE;
        host.toastText(message);
        afterSession();
    }

    public void onTrimMemory(int level) {
        boolean low = level == TRIM_MEMORY_RUNNING_LOW
                || level == TRIM_MEMORY_RUNNING_CRITICAL
                || level >= TRIM_MEMORY_BACKGROUND;
        if (!low) return;
        host.log("trim memory " + level);
        unloadNow();
    }

    public void onPrefsChanged() {
        settings = host.settings();
        if (!settings.enabled || !settings.consent) {
            if (isRecording()) cancelRecording();
            unloadNow();
        }
        if (settings.snoozeUntil <= host.now()) host.cancel(snoozeEndTask);
        if (shrunk && !settings.autoShrink) restore();
        evaluate();
        if (mode == Mode.IDLE) {
            // Size or style may have changed.
            sizePx = host.dpToPx(settings.sizeDp);
            place();
            host.showBubble(bubbleX, bubbleY, sizePx);
            render();
            armShrinkTimer();
        }
        if (!isBusy()) armIdleTimer();
    }

    public void onServiceStopping() {
        host.cancel(holdTask);
        host.cancel(warmUpTask);
        host.cancel(idleTask);
        host.cancel(limitWarningTask);
        host.cancel(limitTask);
        host.cancel(snoozeEndTask);
        host.cancel(shrinkTask);
        if (isRecording()) {
            clearRecordingUi();
            host.cancelRecording();
        }
        if (mode != Mode.HIDDEN) host.hideBubble();
        mode = Mode.HIDDEN;
        setShakeListening(false);
        host.unloadEngineIfIdle();
    }

    /** An external model, language or custom-word reload. */
    public void onEngineReloaded() {
        if (!isBusy()) armIdleTimer();
    }

    // --- Visibility ------------------------------------------------------------

    boolean showAllowed() {
        return baseEligible() && host.now() >= settings.snoozeUntil;
    }

    /** Every show rule except the snooze. */
    private boolean baseEligible() {
        if (!settings.enabled || !settings.consent) return false;
        if (locked) return false;
        if (!keyboardVisible && !settings.showWithoutKeyboard) return false;
        if (field == null || !field.editable || !field.visibleToUser) return false;
        if (pkg == null) return false;
        // The test field counts only inside the package its ID belongs to.
        boolean testField = testFieldId != null && testFieldId.equals(field.viewId)
                && testFieldId.startsWith(pkg + ":");
        if (excludedPackages.contains(pkg) && !testField) return false;
        if (field.isPasswordField() || field.isNonTextField()) return false;
        if (settings.hideInSearch && field.isSearchField()) return false;
        return true;
    }

    private void evaluate() {
        updateShakeListening();
        if (isBusy()) return;
        boolean allowed = showAllowed();
        if (allowed && mode == Mode.HIDDEN) {
            show();
        } else if (allowed) {
            int oldX = bubbleX, oldY = bubbleY;
            place();
            if (!dragging && (oldX != bubbleX || oldY != bubbleY)) host.moveBubble(bubbleX, bubbleY, true);
        } else if (mode != Mode.HIDDEN) {
            hide();
        }
    }

    private void show() {
        mode = Mode.IDLE;
        shrunk = false;
        sizePx = host.dpToPx(settings.sizeDp);
        place();
        host.showBubble(bubbleX, bubbleY, sizePx);
        render();
        host.log("show in " + pkg);
        if (!host.isEngineLoaded() && !loadInFlight) {
            host.postDelayed(warmUpTask, WARM_UP_DELAY_MS);
        }
        armIdleTimer();
        armShrinkTimer();
    }

    private void hide() {
        host.cancel(warmUpTask);
        host.cancel(holdTask);
        host.cancel(shrinkTask);
        shrunk = false;
        swallowGesture = false;
        if (dragging) showSnoozeTarget(false);
        dragging = false;
        fingerDown = false;
        mode = Mode.HIDDEN;
        host.hideBubble();
    }

    private void updateShakeListening() {
        boolean snoozed = host.now() < settings.snoozeUntil;
        setShakeListening(snoozed && baseEligible());
    }

    private void setShakeListening(boolean on) {
        if (on == shakeListening) return;
        shakeListening = on;
        host.setShakeListening(on);
    }

    // --- Recording ---------------------------------------------------------------

    private void onHoldElapsed() {
        if (mode == Mode.IDLE && fingerDown && !dragging) {
            startRecording(Mode.RECORDING_HOLD);
        }
    }

    private void startRecording(Mode recordingMode) {
        host.cancel(idleTask);
        host.cancel(warmUpTask);
        host.cancel(shrinkTask);
        if (!host.isEngineLoaded() && !loadInFlight) requestLoad();
        mode = recordingMode;
        chipArmed = false;
        recordingStartedAt = host.now();
        host.startRecording();
        host.vibrate(BubbleHost.Haptic.START);
        render();
        showChip(true);
        host.postDelayed(limitWarningTask, LIMIT_WARNING_MS);
        host.postDelayed(limitTask, LIMIT_MS);
    }

    private void stopRecording() {
        clearRecordingUi();
        mode = Mode.PROCESSING;
        host.stopRecording();
        host.vibrate(BubbleHost.Haptic.STOP);
        render();
    }

    private void cancelRecording() {
        clearRecordingUi();
        host.cancelRecording();
        host.vibrate(BubbleHost.Haptic.CANCEL);
        mode = Mode.IDLE;
        host.log("recording canceled");
        afterSession();
    }

    private void clearRecordingUi() {
        host.cancel(limitWarningTask);
        host.cancel(limitTask);
        chipArmed = false;
        showChip(false);
    }

    /** Back in IDLE after a result, error or cancel. */
    private void afterSession() {
        render();
        armIdleTimer();
        armShrinkTimer();
        evaluate();
    }

    // --- Auto-shrink ---------------------------------------------------------------

    private void armShrinkTimer() {
        host.cancel(shrinkTask);
        if (mode != Mode.IDLE || shrunk || !settings.autoShrink) return;
        host.postDelayed(shrinkTask, SHRINK_DELAY_MS);
    }

    private void onShrinkElapsed() {
        if (mode != Mode.IDLE || fingerDown || dragging || !settings.autoShrink) return;
        shrunk = true;
        host.setShrunk(true);
    }

    private void restore() {
        shrunk = false;
        host.setShrunk(false);
    }

    private void onLimitWarning() {
        if (isRecording()) host.toast(BubbleHost.Message.LIMIT_WARNING);
    }

    private void onLimitReached() {
        if (isRecording()) {
            fingerDown = false;
            stopRecording();
        }
    }

    private void render() {
        if (mode == Mode.HIDDEN) return;
        host.renderMode(mode, 0f, mode == Mode.PROCESSING && !host.isEngineLoaded());
    }

    // --- Engine policy -------------------------------------------------------------

    private void onWarmUpElapsed() {
        if (mode != Mode.HIDDEN && !host.isEngineLoaded() && !loadInFlight) {
            host.log("warm-up");
            requestLoad();
        }
    }

    private void requestLoad() {
        loadInFlight = true;
        host.loadEngine();
    }

    private void armIdleTimer() {
        host.cancel(idleTask);
        if (isBusy()) return;
        int minutes = settings.unloadMinutes;
        if (minutes <= 0) return;
        host.postDelayed(idleTask, minutes * 60_000L);
    }

    private void onIdleElapsed() {
        if (isBusy()) return;
        if (!host.isEngineLoaded() && !loadInFlight) return;
        host.log("idle timer: unload");
        host.unloadEngineIfIdle();
    }

    private void unloadNow() {
        host.cancel(idleTask);
        if (isBusy()) return;
        if (!host.isEngineLoaded() && !loadInFlight) return;
        host.unloadEngineIfIdle();
    }

    // --- Snooze ----------------------------------------------------------------------

    private void snooze() {
        long until = host.now() + SNOOZE_MS;
        host.saveSnoozeUntil(until);
        settings = host.settings();
        host.log("snoozed");
        host.toast(BubbleHost.Message.SNOOZED);
        hide();
        host.cancel(snoozeEndTask);
        host.postDelayed(snoozeEndTask, SNOOZE_MS);
        updateShakeListening();
    }

    // --- Geometry --------------------------------------------------------------------

    /** Computes the bubble position from the saved side and offset. */
    private void place() {
        int margin = host.dpToPx(EDGE_MARGIN_DP);
        bubbleX = settings.side == SIDE_LEFT ? margin : host.screenWidth() - sizePx - margin;
        int base = anchorY();
        int offset = clampOffset(settings.offsetY);
        bubbleY = base - offset;
    }

    /**
     * Bubble top when the offset is 0: just above the keyboard, or above the
     * navigation bar when no on-screen keyboard is open.
     */
    private int anchorY() {
        int bottom = keyboardVisible && keyboardTop > 0
                ? keyboardTop
                : host.screenHeight() - host.navigationBarHeight();
        return bottom - sizePx - host.dpToPx(KEYBOARD_GAP_DP);
    }

    private int clampOffset(int offset) {
        int max = Math.max(0, anchorY() - host.statusBarHeight());
        return Math.max(0, Math.min(offset, max));
    }

    private void snapToEdge() {
        int centreX = bubbleX + sizePx / 2;
        int side = centreX < host.screenWidth() / 2 ? SIDE_LEFT : SIDE_RIGHT;
        int offset = clampOffset(anchorY() - bubbleY);
        host.saveSide(side);
        host.saveOffsetY(offset);
        settings = host.settings();
        place();
        host.moveBubble(bubbleX, bubbleY, true);
    }

    private int[] chipOrigin() {
        int chip = host.dpToPx(CHIP_DP);
        int gap = host.dpToPx(CHIP_GAP_DP);
        int centreY = bubbleY + sizePx / 2;
        int x = settings.side == SIDE_LEFT
                ? bubbleX + sizePx + gap
                : bubbleX - gap - chip;
        return new int[]{x, centreY - chip / 2};
    }

    private boolean pointInChip(int x, int y, int margin) {
        int chip = host.dpToPx(CHIP_DP);
        int[] o = chipOrigin();
        return x >= o[0] - margin && x <= o[0] + chip + margin
                && y >= o[1] - margin && y <= o[1] + chip + margin;
    }

    private void showChip(boolean visible) {
        int[] o = chipOrigin();
        host.showCancelChip(visible, o[0], o[1], host.dpToPx(CHIP_DP), chipArmed);
    }

    private int[] targetOrigin() {
        int size = host.dpToPx(TARGET_DP);
        int x = (host.screenWidth() - size) / 2;
        int y = host.screenHeight() - host.dpToPx(TARGET_BOTTOM_MARGIN_DP) - size;
        return new int[]{x, y};
    }

    private boolean bubbleCentreInTarget() {
        int size = host.dpToPx(TARGET_DP);
        int[] o = targetOrigin();
        long dx = (bubbleX + sizePx / 2) - (o[0] + size / 2);
        long dy = (bubbleY + sizePx / 2) - (o[1] + size / 2);
        return dx * dx + dy * dy <= (long) size * size;
    }

    private void showSnoozeTarget(boolean visible) {
        int[] o = targetOrigin();
        host.showSnoozeTarget(visible, o[0], o[1], host.dpToPx(TARGET_DP), snoozeArmed);
    }

    // --- Helpers ----------------------------------------------------------------------

    private boolean isRecording() {
        return mode == Mode.RECORDING_TAP || mode == Mode.RECORDING_HOLD;
    }

    private boolean isBusy() {
        return isRecording() || mode == Mode.PROCESSING;
    }
}
