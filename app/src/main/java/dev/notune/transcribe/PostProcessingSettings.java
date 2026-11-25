package dev.notune.transcribe;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * User settings for the optional text post-processing.
 *
 * <p>The API key is not stored here: it goes through {@link SecretStore}.
 * Defaults are deliberately empty (no server, no model, no key); the app must
 * never ship someone else's infrastructure.
 */
final class PostProcessingSettings {

    static final String ASSET_PROMPT = "post_processing_prompt.txt";
    static final int DEFAULT_TIMEOUT_MS = 20_000;

    private static final String PREFS = "post_processing";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_ENDPOINT = "endpoint";
    private static final String KEY_MODEL = "model";
    private static final String KEY_PROMPT = "prompt";

    private static volatile String cachedDefaultPrompt;

    private PostProcessingSettings() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    static String getEndpoint(Context context) {
        return prefs(context).getString(KEY_ENDPOINT, "").trim();
    }

    static void setEndpoint(Context context, String endpoint) {
        prefs(context).edit().putString(KEY_ENDPOINT, endpoint == null ? "" : endpoint.trim()).apply();
    }

    static String getModel(Context context) {
        return prefs(context).getString(KEY_MODEL, "").trim();
    }

    static void setModel(Context context, String model) {
        prefs(context).edit().putString(KEY_MODEL, model == null ? "" : model.trim()).apply();
    }

    static String getApiKey(Context context) {
        return SecretStore.get(context);
    }

    static boolean setApiKey(Context context, String apiKey) {
        return SecretStore.put(context, apiKey == null ? "" : apiKey.trim());
    }

    static boolean hasApiKey(Context context) {
        String key = getApiKey(context);
        return key != null && !key.isEmpty();
    }

    /** The stored instruction, or the bundled Handy-derived default. */
    static String getPrompt(Context context) {
        String stored = prefs(context).getString(KEY_PROMPT, null);
        if (stored != null && !stored.trim().isEmpty()) {
            return stored;
        }
        return defaultPrompt(context);
    }

    static void setPrompt(Context context, String prompt) {
        prefs(context).edit().putString(KEY_PROMPT, prompt == null ? "" : prompt).apply();
    }

    static void resetPrompt(Context context) {
        prefs(context).edit().remove(KEY_PROMPT).apply();
    }

    static boolean isPromptCustomised(Context context) {
        String stored = prefs(context).getString(KEY_PROMPT, null);
        return stored != null && !stored.trim().isEmpty();
    }

    static String defaultPrompt(Context context) {
        if (cachedDefaultPrompt != null) return cachedDefaultPrompt;
        try (InputStream in = context.getAssets().open(ASSET_PROMPT);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            cachedDefaultPrompt = new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            cachedDefaultPrompt = PostProcessingLogic.PLACEHOLDER;
        }
        return cachedDefaultPrompt;
    }

    /**
     * Post-processing needs an endpoint and a model. A key is optional because
     * local servers often do not use one.
     */
    static boolean isConfigured(Context context) {
        return !getEndpoint(context).isEmpty() && !getModel(context).isEmpty();
    }

    static int getTimeoutMs(Context context) {
        return DEFAULT_TIMEOUT_MS;
    }
}
