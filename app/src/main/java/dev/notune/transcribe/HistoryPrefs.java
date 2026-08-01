package dev.notune.transcribe;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class HistoryPrefs {
    private static final String FILE_NAME = "history_retention";
    public static final int DEFAULT_RETENTION = 25;

    private HistoryPrefs() {}

    public static int getRetention(Context ctx) {
        File f = new File(ctx.getFilesDir(), FILE_NAME);
        if (!f.exists()) return DEFAULT_RETENTION;
        try {
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
            return Integer.parseInt(s);
        } catch (IOException | NumberFormatException e) {
            return DEFAULT_RETENTION;
        }
    }

    public static void setRetention(Context ctx, int count) {
        File f = new File(ctx.getFilesDir(), FILE_NAME);
        try {
            Files.write(f.toPath(), String.valueOf(count).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
    }
}
