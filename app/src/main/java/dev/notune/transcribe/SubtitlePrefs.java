package dev.notune.transcribe;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Live-subtitle preferences, stored as a small file in filesDir like the other
 * settings (readable from any process without a content provider).
 */
public final class SubtitlePrefs {
    private static final String FILE_NAME = "subtitle_lines";
    /** Default: classic caption style, last two lines visible. */
    public static final int DEFAULT_MAX_LINES = 2;

    private SubtitlePrefs() {}

    /** Returns the line limit for the subtitle overlay; 0 means unlimited. */
    public static int getMaxLines(Context ctx) {
        File f = new File(ctx.getFilesDir(), FILE_NAME);
        if (!f.exists()) return DEFAULT_MAX_LINES;
        try {
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
            return Integer.parseInt(s);
        } catch (IOException | NumberFormatException e) {
            return DEFAULT_MAX_LINES;
        }
    }

    public static void setMaxLines(Context ctx, int lines) {
        File f = new File(ctx.getFilesDir(), FILE_NAME);
        try {
            Files.write(f.toPath(), String.valueOf(lines).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
    }

    private static final String OVERLAY_Y_FILE = "subtitle_overlay_y";
    /** Default: a small margin above the bottom edge. */
    public static final int DEFAULT_OVERLAY_Y = 100;

    /** Offset of the overlay above the screen bottom, set by dragging it. */
    public static int getOverlayY(Context ctx) {
        File f = new File(ctx.getFilesDir(), OVERLAY_Y_FILE);
        if (!f.exists()) return DEFAULT_OVERLAY_Y;
        try {
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
            return Integer.parseInt(s);
        } catch (IOException | NumberFormatException e) {
            return DEFAULT_OVERLAY_Y;
        }
    }

    public static void setOverlayY(Context ctx, int y) {
        File f = new File(ctx.getFilesDir(), OVERLAY_Y_FILE);
        try {
            Files.write(f.toPath(), String.valueOf(y).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
    }
    private static final String TRANSLATION_TARGET_FILE = "subtitle_translation_target";

    /** BCP-47 target tag, or "auto" to keep the recognized source language. */
    public static String getTranslationTarget(Context ctx) {
        File file = new File(ctx.getFilesDir(), TRANSLATION_TARGET_FILE);
        if (!file.exists()) return SubtitleTranslationTargets.AUTO;
        try {
            String value = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
            return SubtitleTranslationTargets.isValid(value) ? value : SubtitleTranslationTargets.AUTO;
        } catch (IOException e) {
            return SubtitleTranslationTargets.AUTO;
        }
    }

    public static void setTranslationTarget(Context ctx, String tag) {
        File file = new File(ctx.getFilesDir(), TRANSLATION_TARGET_FILE);
        String value = SubtitleTranslationTargets.isValid(tag) ? tag : SubtitleTranslationTargets.AUTO;
        try {
            Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
    }
}
