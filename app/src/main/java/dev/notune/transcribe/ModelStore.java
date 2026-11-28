package dev.notune.transcribe;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Which speech models the app can use: the bundled GigaAM model (absent in
 * F-Droid builds) and GGUF files imported into {@code files/models/}. The
 * selection is the {@code active_model} marker file read by the Rust engine
 * loader: the file name of an imported model, absent/empty = bundled model.
 */
public final class ModelStore {
    private static final String TAG = "ModelStore";
    private static final String ACTIVE_MODEL_FILE = "active_model";
    /** Asset directory of the bundled model (see src/assets.rs). */
    private static final String BUILTIN_ASSET_DIR = "builtin-model";

    /** Engine status when there is nothing to load (NO_MODEL_STATUS in src/engine.rs). */
    public static final String NO_MODEL_STATUS = "Error: no speech model installed";

    /** The bundled model, offered as the first download when it isn't bundled. */
    public static final String RECOMMENDED_NAME = "GigaAM v3 E2E RNNT";
    public static final String RECOMMENDED_SIZE = "228 MB";
    public static final String RECOMMENDED_URL =
            "https://huggingface.co/handy-computer/gigaam-v3-e2e-rnnt-gguf/resolve/main/gigaam-v3-e2e-rnnt-Q6_K.gguf";

    private static Boolean builtin;

    private ModelStore() {}

    /** True if this build bundles a model (F-Droid builds don't). */
    public static synchronized boolean hasBuiltinModel(Context ctx) {
        if (builtin == null) {
            boolean found = false;
            try {
                String[] names = ctx.getAssets().list(BUILTIN_ASSET_DIR);
                if (names != null) {
                    for (String name : names) {
                        if (isGguf(name)) found = true;
                    }
                }
            } catch (IOException e) {
                Log.w(TAG, "Failed to list bundled model assets", e);
            }
            builtin = found;
        }
        return builtin;
    }

    public static boolean hasAnyModel(Context ctx) {
        return hasBuiltinModel(ctx) || !importedModels(ctx).isEmpty();
    }

    public static File modelsDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "models");
        dir.mkdirs();
        return dir;
    }

    /** File names of the imported models, sorted for display. */
    public static List<String> importedModels(Context ctx) {
        List<String> names = new ArrayList<>();
        File[] files = modelsDir(ctx).listFiles((dir, name) -> isGguf(name));
        if (files != null) {
            for (File f : files) names.add(f.getName());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    static boolean isGguf(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".gguf");
    }

    /** The selected imported model, or "" for the bundled one. */
    public static String activeModel(Context ctx) {
        File f = new File(ctx.getFilesDir(), ACTIVE_MODEL_FILE);
        if (!f.exists()) return "";
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    /** Selects an imported model; null or "" selects the bundled one. */
    public static void setActiveModel(Context ctx, String fileName) {
        File f = new File(ctx.getFilesDir(), ACTIVE_MODEL_FILE);
        if (fileName == null || fileName.isEmpty()) {
            f.delete();
            return;
        }
        try {
            Files.write(f.toPath(), fileName.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.e(TAG, "Failed to write " + ACTIVE_MODEL_FILE, e);
        }
    }

    /**
     * Keeps the selection pointing at a model that exists: a deleted model
     * falls back to the bundled one, or without one to the first imported
     * model. Returns the (possibly updated) selection.
     */
    public static String ensureValidSelection(Context ctx) {
        String active = activeModel(ctx);
        List<String> names = importedModels(ctx);
        if (!active.isEmpty() && names.contains(active)) return active;
        if (active.isEmpty() && hasBuiltinModel(ctx)) return active;

        String fallback = hasBuiltinModel(ctx) || names.isEmpty() ? "" : names.get(0);
        if (!fallback.equals(active)) setActiveModel(ctx, fallback);
        return fallback;
    }

    /** An engine status for display, with the no-model error translated. */
    public static String displayStatus(Context ctx, String status) {
        return NO_MODEL_STATUS.equals(status) ? ctx.getString(R.string.no_model_short) : status;
    }
}
