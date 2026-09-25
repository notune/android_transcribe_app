package dev.notune.transcribe;

/**
 * The boundary between {@link BubbleController} and Android. The accessibility
 * service implements it; tests use a fake. Every method is called on the main
 * thread, and every async result comes back through a controller input.
 */
public interface BubbleHost {

    enum Haptic { START, STOP, CANCEL, SNOOZE_ARMED }

    enum Message { INSERTED, COPIED, LIMIT_WARNING, LOAD_FAILED, SNOOZED }

    /** Bubble settings, read once and refreshed on onPrefsChanged(). */
    final class Settings {
        public final boolean enabled;
        public final boolean consent;
        public final boolean hideInSearch;
        /** Show on a focused field even when no on-screen keyboard is open. */
        public final boolean showWithoutKeyboard;
        public final int unloadMinutes;
        /** Full-size window (touch target) in dp, from {@link #scaleIndex}. */
        public final int sizeDp;
        public final int side;
        public final int offsetY;
        public final long snoozeUntil;
        // Appearance (see BubbleAppearance).
        /** A {@link BubbleAppearance.Style} ordinal. Light/dark comes from the app theme. */
        public final int style;
        public final int scaleIndex;
        public final int opacity;
        public final boolean autoShrink;
        public final boolean shrinkDot;

        public Settings(boolean enabled, boolean consent, boolean hideInSearch,
                        boolean showWithoutKeyboard, int unloadMinutes, int side,
                        int offsetY, long snoozeUntil, int style,
                        int scaleIndex, int opacity, boolean autoShrink, boolean shrinkDot) {
            this.enabled = enabled;
            this.consent = consent;
            this.hideInSearch = hideInSearch;
            this.showWithoutKeyboard = showWithoutKeyboard;
            this.unloadMinutes = unloadMinutes;
            this.sizeDp = BubbleAppearance.windowDpForIndex(scaleIndex);
            this.side = side;
            this.offsetY = offsetY;
            this.snoozeUntil = snoozeUntil;
            this.style = style;
            this.scaleIndex = scaleIndex;
            this.opacity = opacity;
            this.autoShrink = autoShrink;
            this.shrinkDot = shrinkDot;
        }
    }

    // Overlay
    void showBubble(int x, int y, int sizePx);
    /** @param animate glide there (keyboard height change, edge snap); false follows a drag */
    void moveBubble(int x, int y, boolean animate);
    void hideBubble();
    void renderMode(BubbleController.Mode mode, float audioLevel, boolean modelLoading);
    void showCancelChip(boolean visible, int x, int y, int sizePx, boolean armed);
    void showSnoozeTarget(boolean visible, int x, int y, int sizePx, boolean armed);
    /** Shrinks (idle auto-shrink) or restores the bubble; the host picks small or dot. */
    void setShrunk(boolean shrunk);

    // Recording
    void startRecording();
    void stopRecording();
    void cancelRecording();

    // Engine; results come back via onEngineLoaded / onUnloadResult
    boolean isEngineLoaded();
    void loadEngine();
    void unloadEngineIfIdle();

    // Output
    /** Puts the text on the clipboard and pastes it; true if pasted. */
    boolean insertText(String text);
    void saveHistory(String text);
    void vibrate(Haptic kind);
    void toast(Message message);
    void toastText(String text);
    void setShakeListening(boolean on);

    // Settings
    Settings settings();
    void saveSide(int side);
    void saveOffsetY(int offsetY);
    void saveSnoozeUntil(long epochMs);

    // Environment
    int dpToPx(int dp);
    int screenWidth();
    int screenHeight();
    int statusBarHeight();
    int navigationBarHeight();
    long now();
    void postDelayed(Runnable r, long ms);
    void cancel(Runnable r);
    void log(String message);
}
