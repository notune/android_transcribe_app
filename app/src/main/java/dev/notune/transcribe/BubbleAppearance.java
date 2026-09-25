package dev.notune.transcribe;

/**
 * How the Flow bubble looks right now. {@link #resolve} is a pure function of
 * the settings, the night mode, blur support and the bubble mode, so the real
 * bubble and the style-screen preview always agree. No Android imports.
 */
public final class BubbleAppearance {

    /** BLUR is neutral frosted glass; TINTED_BLUR tints the blur with the Material theme. */
    public enum Style { MATERIAL, MONO, BLUR, TINTED_BLUR }

    public enum Shrink { NONE, SMALL, DOT }

    /** Size choices as factors of {@link #BASE_DP}. */
    public static final float[] SCALES = {0.7f, 0.85f, 1.0f, 1.15f};
    public static final int DEFAULT_SCALE_INDEX = 2;
    public static final int BASE_DP = 56;
    /** The window never gets smaller than this, so the bubble stays easy to hit. */
    public static final int MIN_TOUCH_DP = 48;
    /** Touch target of a shrunk bubble. */
    public static final int SHRUNK_TOUCH_DP = 40;
    public static final float SMALL_FACTOR = 0.6f;
    public static final int DOT_DP = 12;

    public final Style style;
    public final float scale;
    public final float alpha;
    public final Shrink shrink;
    public final boolean blur;

    BubbleAppearance(Style style, float scale, float alpha, Shrink shrink, boolean blur) {
        this.style = style;
        this.scale = scale;
        this.alpha = alpha;
        this.shrink = shrink;
        this.blur = blur;
    }

    /**
     * @param night  light or dark, from the app's Appearance setting
     * @param shrunk the controller's shrink state; only honoured in IDLE
     */
    public static BubbleAppearance resolve(BubbleHost.Settings s, boolean night,
                                           boolean blurSupported,
                                           BubbleController.Mode mode, boolean shrunk) {
        Style style = styleOf(s.style);
        boolean idle = mode == BubbleController.Mode.IDLE || mode == BubbleController.Mode.HIDDEN;
        float alpha = idle ? normalizeOpacity(s.opacity) / 100f : 1f;
        Shrink shrink = Shrink.NONE;
        if (idle && shrunk && s.autoShrink) {
            shrink = s.shrinkDot ? Shrink.DOT : Shrink.SMALL;
        }
        boolean blur = isBlurStyle(style) && blurSupported;
        return new BubbleAppearance(style, scaleOf(s.scaleIndex), alpha, shrink, blur);
    }

    public static boolean isBlurStyle(Style style) {
        return style == Style.BLUR || style == Style.TINTED_BLUR;
    }

    /** Visible disc diameter at full size, in dp. */
    public int discDp() {
        return Math.round(BASE_DP * scale);
    }

    /** Visible disc diameter with the current shrink applied, in dp. */
    public int currentDiscDp() {
        switch (shrink) {
            case SMALL: return Math.round(discDp() * SMALL_FACTOR);
            case DOT: return DOT_DP;
            default: return discDp();
        }
    }

    /** Window (touch target) size with the current shrink applied, in dp. */
    public int currentWindowDp() {
        return shrink == Shrink.NONE ? windowDp(scale) : Math.max(currentDiscDp(), SHRUNK_TOUCH_DP);
    }

    /** Full-size window for a scale index: the disc, but at least the touch minimum. */
    public static int windowDpForIndex(int scaleIndex) {
        return windowDp(scaleOf(scaleIndex));
    }

    private static int windowDp(float scale) {
        return Math.max(Math.round(BASE_DP * scale), MIN_TOUCH_DP);
    }

    public static Style styleOf(int value) {
        Style[] all = Style.values();
        return value >= 0 && value < all.length ? all[value] : Style.MATERIAL;
    }

    public static float scaleOf(int index) {
        return index >= 0 && index < SCALES.length ? SCALES[index] : SCALES[DEFAULT_SCALE_INDEX];
    }

    /** Snaps to 20..100 in steps of 10. */
    public static int normalizeOpacity(int percent) {
        int snapped = Math.round(percent / 10f) * 10;
        return Math.max(20, Math.min(100, snapped));
    }
}
