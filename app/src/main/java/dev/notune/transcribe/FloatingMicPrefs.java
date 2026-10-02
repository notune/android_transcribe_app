package dev.notune.transcribe;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

/** Floating mic button preferences, stored as small files in filesDir like the other settings. */
public final class FloatingMicPrefs {
    private static final String ENABLED_FILE = "floating_mic";
    private static final String POS_FILE = "floating_mic_positions";
    private static final String SETTINGS_FILE = "floating_mic_settings";

    private FloatingMicPrefs() {}

    public static boolean isEnabled(Context ctx) {
        return new File(ctx.getFilesDir(), ENABLED_FILE).exists();
    }

    public static void setEnabled(Context ctx, boolean on) {
        File f = new File(ctx.getFilesDir(), ENABLED_FILE);
        if (on) {
            try { f.createNewFile(); } catch (IOException ignored) { }
        } else {
            f.delete();
        }
    }

    // --- Appearance ----------------------------------------------------------

    public static final int SIDE_FREE = 0;
    public static final int SIDE_LEFT = 1;
    public static final int SIDE_RIGHT = 2;

    public static final int SIZE_MIN_DP = 36, SIZE_MAX_DP = 96, SIZE_DEFAULT_DP = 56;
    public static final int OPACITY_MIN = 20, OPACITY_MAX = 100;

    public static int getSizeDp(Context ctx) {
        return clamp(readInt(ctx, "size", SIZE_DEFAULT_DP), SIZE_MIN_DP, SIZE_MAX_DP);
    }

    public static void setSizeDp(Context ctx, int dp) {
        writeSetting(ctx, "size", String.valueOf(dp));
    }

    /** Opacity in percent, 20..100. */
    public static int getOpacity(Context ctx) {
        return clamp(readInt(ctx, "opacity", OPACITY_MAX), OPACITY_MIN, OPACITY_MAX);
    }

    public static void setOpacity(Context ctx, int percent) {
        writeSetting(ctx, "opacity", String.valueOf(percent));
    }

    /** One of SIDE_FREE, SIDE_LEFT, SIDE_RIGHT. */
    public static int getSide(Context ctx) {
        return clamp(readInt(ctx, "side", SIDE_FREE), SIDE_FREE, SIDE_RIGHT);
    }

    public static void setSide(Context ctx, int side) {
        writeSetting(ctx, "side", String.valueOf(side));
    }

    // --- Position, remembered per screen layout -------------------------------
    // A foldable has several screen layouts (folded/unfolded x portrait/landscape),
    // each with its own size, so the position is stored as a fraction of the free
    // area, keyed by the screen size in pixels.

    /** Returns {xFraction, yFraction} (0..1) for this screen layout, or null if never moved there. */
    public static float[] getPosition(Context ctx, String screenKey) {
        String v = readProps(ctx, POS_FILE).getProperty(screenKey);
        if (v == null) return null;
        try {
            String[] p = v.split(",");
            return new float[]{Float.parseFloat(p[0]), Float.parseFloat(p[1])};
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static void setPosition(Context ctx, String screenKey, float xFrac, float yFrac) {
        Properties props = readProps(ctx, POS_FILE);
        props.setProperty(screenKey, xFrac + "," + yFrac);
        writeProps(ctx, POS_FILE, props);
    }

    public static void resetPositions(Context ctx) {
        new File(ctx.getFilesDir(), POS_FILE).delete();
    }

    // --- Storage -------------------------------------------------------------

    private static int readInt(Context ctx, String key, int def) {
        try {
            return Integer.parseInt(readProps(ctx, SETTINGS_FILE).getProperty(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static void writeSetting(Context ctx, String key, String value) {
        Properties props = readProps(ctx, SETTINGS_FILE);
        props.setProperty(key, value);
        writeProps(ctx, SETTINGS_FILE, props);
    }

    private static Properties readProps(Context ctx, String name) {
        Properties props = new Properties();
        File f = new File(ctx.getFilesDir(), name);
        if (f.exists()) {
            try (InputStream in = new FileInputStream(f)) {
                props.load(in);
            } catch (IOException ignored) { }
        }
        return props;
    }

    private static void writeProps(Context ctx, String name, Properties props) {
        try (OutputStream out = new FileOutputStream(new File(ctx.getFilesDir(), name))) {
            props.store(out, null);
        } catch (IOException ignored) { }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
