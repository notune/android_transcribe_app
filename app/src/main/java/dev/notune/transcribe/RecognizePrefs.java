package dev.notune.transcribe;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Position of the voice-input popup panel, stored as a small file in filesDir
 * like the other settings. No file (the default) keeps the panel along the
 * bottom; "right"/"left" show it as a narrow vertical panel at that edge.
 */
public final class RecognizePrefs {
    private static final String FILE_NAME = "recognize_side";

    /** Edges the panel can take. Physical, not start/end. */
    public static final String SIDE_RIGHT = "right";
    public static final String SIDE_LEFT = "left";

    private RecognizePrefs() {}

    /**
     * Returns the edge the panel sits on, or "" for the default bottom panel.
     * Anything else on disk reads as "": the settings screen and the popup read
     * the same file, so a value one of them cannot make sense of must not leave
     * them disagreeing about whether the setting is on.
     */
    public static String getPanelSide(Context ctx) {
        File f = new File(ctx.getFilesDir(), FILE_NAME);
        if (!f.exists()) return "";
        try {
            String side = new String(
                    Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
            return SIDE_RIGHT.equals(side) || SIDE_LEFT.equals(side) ? side : "";
        } catch (IOException e) {
            return "";
        }
    }

    /** An empty side deletes the file, so absence stays the bottom default. */
    public static void setPanelSide(Context ctx, String side) {
        File f = new File(ctx.getFilesDir(), FILE_NAME);
        if (side == null || side.isEmpty()) {
            f.delete();
            return;
        }
        try {
            Files.write(f.toPath(), side.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
    }
}
