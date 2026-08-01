package dev.notune.transcribe;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class BubblePrefs {
    private static final String POS_X_FILE = "bubble_x";
    private static final String POS_Y_FILE = "bubble_y";
    private static final String ENABLED_FILE = "bubble_enabled";
    private static final String A11Y_CONSENT_FILE = "a11y_insertion_consent";
    private static final String UNLOAD_MINUTES_FILE = "bubble_unload_minutes";
    private static final String SIZE_DP_FILE = "bubble_size_dp";

    /** Idle-unload choices offered in settings, in minutes. 0 = never. */
    public static final int[] UNLOAD_MINUTES_CHOICES = {0, 5, 15, 30};
    /** Default idle-unload interval for users who never changed the setting. */
    public static final int DEFAULT_UNLOAD_MINUTES = 15;
    /** Bubble diameter choices offered in settings, in dp. */
    public static final int[] SIZE_DP_CHOICES = {48, 56, 72};
    /** Default bubble diameter (also the minimum usable touch target). */
    public static final int DEFAULT_SIZE_DP = 56;
    /** Smallest diameter we allow; keeps a usable 48dp touch target. */
    public static final int MIN_SIZE_DP = 48;

    private BubblePrefs() {}

    public static int getX(Context ctx) {
        return readInt(ctx, POS_X_FILE, -1);
    }

    public static void setX(Context ctx, int x) {
        writeInt(ctx, POS_X_FILE, x);
    }

    public static int getY(Context ctx) {
        return readInt(ctx, POS_Y_FILE, -1);
    }

    public static void setY(Context ctx, int y) {
        writeInt(ctx, POS_Y_FILE, y);
    }

    public static boolean isEnabled(Context ctx) {
        return new File(ctx.getFilesDir(), ENABLED_FILE).exists();
    }

    public static void setEnabled(Context ctx, boolean enabled) {
        File f = new File(ctx.getFilesDir(), ENABLED_FILE);
        if (enabled) {
            try { f.createNewFile(); } catch (IOException ignored) { }
        } else {
            f.delete();
        }
    }

    public static boolean hasA11yConsent(Context ctx) {
        return new File(ctx.getFilesDir(), A11Y_CONSENT_FILE).exists();
    }

    public static void setA11yConsent(Context ctx, boolean consent) {
        File f = new File(ctx.getFilesDir(), A11Y_CONSENT_FILE);
        if (consent) {
            try { f.createNewFile(); } catch (IOException ignored) { }
        } else {
            f.delete();
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

    /**
     * Bubble diameter in dp. Absent setting yields the default; a stored value
     * is snapped to the nearest offered choice and never below {@link
     * #MIN_SIZE_DP}, keeping a usable touch target.
     */
    public static int getSizeDp(Context ctx) {
        return normalizeSizeDp(readInt(ctx, SIZE_DP_FILE, DEFAULT_SIZE_DP));
    }

    public static void setSizeDp(Context ctx, int dp) {
        writeInt(ctx, SIZE_DP_FILE, normalizeSizeDp(dp));
    }

    /** Snaps an arbitrary minute value to the nearest offered unload choice. */
    static int normalizeUnloadMinutes(int minutes) {
        return nearest(minutes, UNLOAD_MINUTES_CHOICES, DEFAULT_UNLOAD_MINUTES);
    }

    /** Snaps an arbitrary dp value to the nearest offered size, ≥ minimum. */
    static int normalizeSizeDp(int dp) {
        int chosen = nearest(dp, SIZE_DP_CHOICES, DEFAULT_SIZE_DP);
        return Math.max(chosen, MIN_SIZE_DP);
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

    private static int readInt(Context ctx, String name, int def) {
        File f = new File(ctx.getFilesDir(), name);
        if (!f.exists()) return def;
        try {
            return Integer.parseInt(
                    new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim());
        } catch (IOException | NumberFormatException e) {
            return def;
        }
    }

    private static void writeInt(Context ctx, String name, int value) {
        File f = new File(ctx.getFilesDir(), name);
        try {
            Files.write(f.toPath(), String.valueOf(value).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
    }
}
