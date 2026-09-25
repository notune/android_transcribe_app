package dev.notune.transcribe;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class BubblePrefs {
    private static final String DISABLED_FILE = "bubble_disabled";
    private static final String SIDE_FILE = "bubble_side";
    private static final String OFFSET_Y_FILE = "bubble_offset_y";
    private static final String SNOOZE_UNTIL_FILE = "bubble_snooze_until";
    private static final String HIDE_SEARCH_FILE = "bubble_hide_search";
    private static final String NO_KEYBOARD_FILE = "bubble_no_keyboard";
    private static final String A11Y_CONSENT_FILE = "a11y_insertion_consent";
    private static final String UNLOAD_MINUTES_FILE = "bubble_unload_minutes";
    /** Phase-1 size setting; migrated to {@link #SCALE_INDEX_FILE} on first read. */
    private static final String LEGACY_SIZE_DP_FILE = "bubble_size_dp";
    private static final String STYLE_FILE = "bubble_style";
    /** Early phase-2 builds stored a style per theme; migrated to {@link #STYLE_FILE}. */
    private static final String LEGACY_STYLE_LIGHT_FILE = "bubble_style_light";
    private static final String LEGACY_STYLE_DARK_FILE = "bubble_style_dark";
    private static final String SCALE_INDEX_FILE = "bubble_scale_index";
    private static final String OPACITY_FILE = "bubble_opacity";
    private static final String AUTO_SHRINK_FILE = "bubble_auto_shrink";
    private static final String SHRINK_DOT_FILE = "bubble_shrink_dot";

    /** Idle-unload choices offered in settings, in minutes. 0 = never. */
    public static final int[] UNLOAD_MINUTES_CHOICES = {0, 5, 15, 30};
    /** Default idle-unload interval for users who never changed the setting. */
    public static final int DEFAULT_UNLOAD_MINUTES = 15;
    /** Default docking edge. */
    public static final int DEFAULT_SIDE = BubbleController.SIDE_RIGHT;

    private BubblePrefs() {}

    /** Master switch "Show bubble in text fields". Defaults to on. */
    public static boolean isEnabled(Context ctx) {
        return !new File(ctx.getFilesDir(), DISABLED_FILE).exists();
    }

    public static void setEnabled(Context ctx, boolean enabled) {
        setFlag(ctx, DISABLED_FILE, !enabled);
    }

    public static boolean hasA11yConsent(Context ctx) {
        return new File(ctx.getFilesDir(), A11Y_CONSENT_FILE).exists();
    }

    public static void setA11yConsent(Context ctx, boolean consent) {
        setFlag(ctx, A11Y_CONSENT_FILE, consent);
    }

    public static boolean isHideInSearch(Context ctx) {
        return new File(ctx.getFilesDir(), HIDE_SEARCH_FILE).exists();
    }

    public static void setHideInSearch(Context ctx, boolean hide) {
        setFlag(ctx, HIDE_SEARCH_FILE, hide);
    }

    /**
     * Show the bubble on a focused field with no on-screen keyboard (physical
     * keyboard, tablets). Off by default, like Inspiration App.
     */
    public static boolean isShowWithoutKeyboard(Context ctx) {
        return new File(ctx.getFilesDir(), NO_KEYBOARD_FILE).exists();
    }

    public static void setShowWithoutKeyboard(Context ctx, boolean show) {
        setFlag(ctx, NO_KEYBOARD_FILE, show);
    }

    /** Docking edge: {@link BubbleController#SIDE_LEFT} or SIDE_RIGHT. */
    public static int getSide(Context ctx) {
        return normalizeSide(readInt(ctx, SIDE_FILE, DEFAULT_SIDE));
    }

    public static void setSide(Context ctx, int side) {
        writeInt(ctx, SIDE_FILE, normalizeSide(side));
    }

    /** Vertical offset above the keyboard, in px. Never negative. */
    public static int getOffsetY(Context ctx) {
        return Math.max(0, readInt(ctx, OFFSET_Y_FILE, 0));
    }

    public static void setOffsetY(Context ctx, int offsetY) {
        writeInt(ctx, OFFSET_Y_FILE, Math.max(0, offsetY));
    }

    /** Snooze end as epoch ms; 0 = not snoozed. */
    public static long getSnoozeUntil(Context ctx) {
        return Math.max(0, readLong(ctx, SNOOZE_UNTIL_FILE, 0));
    }

    public static void setSnoozeUntil(Context ctx, long epochMs) {
        if (epochMs <= 0) {
            new File(ctx.getFilesDir(), SNOOZE_UNTIL_FILE).delete();
        } else {
            writeString(ctx, SNOOZE_UNTIL_FILE, String.valueOf(epochMs));
        }
    }

    /**
     * Idle-unload interval in minutes (0 = never). Absent setting yields the
     * default; a stored value is snapped to the nearest offered choice so a
     * hand-edited or stale file can't select an unsupported interval.
     */
    public static int getUnloadMinutes(Context ctx) {
        return normalizeUnloadMinutes(readInt(ctx, UNLOAD_MINUTES_FILE, DEFAULT_UNLOAD_MINUTES));
    }

    public static void setUnloadMinutes(Context ctx, int minutes) {
        writeInt(ctx, UNLOAD_MINUTES_FILE, normalizeUnloadMinutes(minutes));
    }

    // --- Appearance ----------------------------------------------------------------

    /**
     * The bubble style, a {@link BubbleAppearance.Style} ordinal. Light or dark
     * comes from the app's Appearance setting, not from here.
     */
    public static int getStyle(Context ctx) {
        File legacy = new File(ctx.getFilesDir(), LEGACY_STYLE_LIGHT_FILE);
        if (legacy.exists() && !new File(ctx.getFilesDir(), STYLE_FILE).exists()) {
            // Old ordinals: 0 MATERIAL, 1 MONO, 2 GLASS (removed), 3 BLUR.
            int old = readInt(ctx, LEGACY_STYLE_LIGHT_FILE, 0);
            BubbleAppearance.Style style = old == 1 ? BubbleAppearance.Style.MONO
                    : old >= 2 ? BubbleAppearance.Style.BLUR : BubbleAppearance.Style.MATERIAL;
            setStyle(ctx, style);
            legacy.delete();
            new File(ctx.getFilesDir(), LEGACY_STYLE_DARK_FILE).delete();
        }
        return BubbleAppearance.styleOf(readInt(ctx, STYLE_FILE, 0)).ordinal();
    }

    public static void setStyle(Context ctx, BubbleAppearance.Style style) {
        writeInt(ctx, STYLE_FILE, style.ordinal());
    }

    /**
     * Whether the bubble draws in dark colours: the app's Appearance setting
     * (Light / Dark), or the system night mode for "System default".
     */
    public static boolean isNight(Context ctx) {
        int mode = ThemePrefs.getMode(ctx);
        if (mode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES) return true;
        if (mode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO) return false;
        int ui = ctx.getApplicationContext().getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return ui == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * Index into {@link BubbleAppearance#SCALES}. Migrates the phase-1
     * {@code bubble_size_dp} (48 → 0.85, 56 → 1.0, 72 → 1.15) on first read.
     */
    public static int getScaleIndex(Context ctx) {
        File legacy = new File(ctx.getFilesDir(), LEGACY_SIZE_DP_FILE);
        if (legacy.exists() && !new File(ctx.getFilesDir(), SCALE_INDEX_FILE).exists()) {
            int dp = readInt(ctx, LEGACY_SIZE_DP_FILE, 56);
            setScaleIndex(ctx, dp <= 52 ? 1 : dp >= 64 ? 3 : 2);
            legacy.delete();
        }
        return normalizeScaleIndex(readInt(ctx, SCALE_INDEX_FILE, BubbleAppearance.DEFAULT_SCALE_INDEX));
    }

    public static void setScaleIndex(Context ctx, int index) {
        writeInt(ctx, SCALE_INDEX_FILE, normalizeScaleIndex(index));
    }

    /** Idle opacity in percent, 20..100 in steps of 10. */
    public static int getOpacity(Context ctx) {
        return BubbleAppearance.normalizeOpacity(readInt(ctx, OPACITY_FILE, 100));
    }

    public static void setOpacity(Context ctx, int percent) {
        writeInt(ctx, OPACITY_FILE, BubbleAppearance.normalizeOpacity(percent));
    }

    public static boolean isAutoShrink(Context ctx) {
        return new File(ctx.getFilesDir(), AUTO_SHRINK_FILE).exists();
    }

    public static void setAutoShrink(Context ctx, boolean on) {
        setFlag(ctx, AUTO_SHRINK_FILE, on);
    }

    public static boolean isShrinkDot(Context ctx) {
        return new File(ctx.getFilesDir(), SHRINK_DOT_FILE).exists();
    }

    public static void setShrinkDot(Context ctx, boolean on) {
        setFlag(ctx, SHRINK_DOT_FILE, on);
    }

    static int normalizeScaleIndex(int index) {
        return index >= 0 && index < BubbleAppearance.SCALES.length
                ? index : BubbleAppearance.DEFAULT_SCALE_INDEX;
    }

    /** All bubble settings as one snapshot for the controller. */
    public static BubbleHost.Settings load(Context ctx) {
        return new BubbleHost.Settings(
                isEnabled(ctx), hasA11yConsent(ctx), isHideInSearch(ctx),
                isShowWithoutKeyboard(ctx), getUnloadMinutes(ctx), getSide(ctx),
                getOffsetY(ctx), getSnoozeUntil(ctx), getStyle(ctx), getScaleIndex(ctx), getOpacity(ctx), isAutoShrink(ctx), isShrinkDot(ctx));
    }

    static int normalizeSide(int side) {
        return side == BubbleController.SIDE_LEFT
                ? BubbleController.SIDE_LEFT : BubbleController.SIDE_RIGHT;
    }

    /** Snaps an arbitrary minute value to the nearest offered unload choice. */
    static int normalizeUnloadMinutes(int minutes) {
        return nearest(minutes, UNLOAD_MINUTES_CHOICES, DEFAULT_UNLOAD_MINUTES);
    }

    private static int nearest(int value, int[] choices, int fallback) {
        if (choices.length == 0) return fallback;
        int best = choices[0];
        int bestDist = Math.abs(value - best);
        for (int i = 1; i < choices.length; i++) {
            int dist = Math.abs(value - choices[i]);
            if (dist < bestDist) {
                best = choices[i];
                bestDist = dist;
            }
        }
        return best;
    }

    private static void setFlag(Context ctx, String name, boolean present) {
        File f = new File(ctx.getFilesDir(), name);
        if (present) {
            try { f.createNewFile(); } catch (IOException ignored) { }
        } else {
            f.delete();
        }
    }

    private static int readInt(Context ctx, String name, int def) {
        long v = readLong(ctx, name, def);
        return v > Integer.MAX_VALUE || v < Integer.MIN_VALUE ? def : (int) v;
    }

    private static long readLong(Context ctx, String name, long def) {
        File f = new File(ctx.getFilesDir(), name);
        if (!f.exists()) return def;
        try {
            return Long.parseLong(
                    new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim());
        } catch (IOException | NumberFormatException e) {
            return def;
        }
    }

    private static void writeInt(Context ctx, String name, int value) {
        writeString(ctx, name, String.valueOf(value));
    }

    private static void writeString(Context ctx, String name, String value) {
        File f = new File(ctx.getFilesDir(), name);
        try {
            Files.write(f.toPath(), value.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
    }
}
