package dev.notune.transcribe;

import android.util.Log;

import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * On-device text translation for live subtitles backed by Google ML Kit
 * <p>ML Kit translates fully on-device once the per-pair language packs are
 * downloaded (the first use downloads them through Google Play Services;
 * afterwards no network involvement is needed for the actual translation). On
 * devices without Play Services — or when a pack download fails — the request
 * falls back to the source text, so subtitles never disappear: the safe-fallback
 * guarantee is the same one the ASR and the AI post-processor already provide.
 *
 * <p>Every language exposed by the installed ML Kit Translation SDK is
 * accepted. Translators are cached per (source, target) pair and
 * pack-download state is remembered per pair for the session.
 */
public final class OnDeviceSubtitleTranslator implements SubtitleTranslator {
    private static final String TAG = "OnDeviceSubtitleTranslator";

    private final Map<String, Translator> translators = new HashMap<>();
    private final Set<String> downloaded = new HashSet<>();
    private final Set<String> failed = new HashSet<>();
    private Translator probe;
    private boolean availabilityChecked = false;
    private boolean available = false;

    /** Maps an ML Kit language code to its {@link TranslateLanguage} constant. */
    private LanguageIdentifier languageIdentifier;
    private boolean languageIdUnavailable = false;
    /** Last source language confidently detected in this session. */
    private String sessionSourceLanguage;

    /** Receives the resolved source language (null = unknown, keep original). */
    private interface SourceSink {
        void onSource(String sourceLanguage);
    }
    private static String languageConstant(String code) {
        return SubtitleTranslationTargets.mlKitCode(code);
    }

    /** Callback for preparing a target language model before the user proceeds. */
    public interface ModelDownloadCallback {
        void onReady();
        void onFailure(Throwable error);
    }

    /**
     * Ensures the selected target language model is downloaded before the
     * translation setting is saved. When the model is already installed,
     * ML Kit completes this task immediately.
     */
    public void prepareModel(String sourceLanguage, String targetLanguage,
                             ModelDownloadCallback callback) {
        final String source = languageConstant(sourceLanguage);
        final String target = languageConstant(targetLanguage);
        if (source == null || target == null) {
            callback.onFailure(new IllegalArgumentException("Unsupported translation language"));
            return;
        }
        if (source.equals(target)) {
            callback.onReady();
            return;
        }

        final String key = source + ":" + target;
        final Translator translator;
        synchronized (this) {
            if (downloaded.contains(key)) {
                callback.onReady();
                return;
            }
            try {
                Translator existing = translators.get(key);
                if (existing != null) {
                    translator = existing;
                } else {
                    TranslatorOptions options = new TranslatorOptions.Builder()
                            .setSourceLanguage(source)
                            .setTargetLanguage(target)
                            .build();
                    translator = Translation.getClient(options);
                    translators.put(key, translator);
                }
            } catch (Throwable error) {
                callback.onFailure(error);
                return;
            }
        }

        translator.downloadModelIfNeeded()
                .addOnSuccessListener(unused -> {
                    synchronized (OnDeviceSubtitleTranslator.this) {
                        downloaded.add(key);
                        failed.remove(key);
                    }
                    callback.onReady();
                })
                .addOnFailureListener(callback::onFailure);
    }

    /**
     * Lazy availability probe. Creating a client on a device without ML Kit /
     * Google Play Services fails fast, so we remember the outcome for the
     * session and keep showing the original language instead of spamming
     * failures per segment.
     */
    private synchronized boolean isAvailable() {
        if (availabilityChecked) return available;
        availabilityChecked = true;
        try {
            TranslatorOptions probeOpts = new TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.ENGLISH)
                    .setTargetLanguage(TranslateLanguage.PERSIAN)
                    .build();
            probe = Translation.getClient(probeOpts);
            available = true;
        } catch (Throwable t) {
            Log.w(TAG, "ML Kit translation unavailable on this device; "
                    + "subtitles stay in the original language", t);
        }
        return available;
    }

    @Override
    public void translate(String sourceText, String sourceLanguage, String targetLanguage,
                          Callback callback) {
        if (sourceText == null || sourceText.isEmpty() || targetLanguage == null) {

            callback.onSuccess(sourceText);
            return;
        }
        if (sourceLanguage != null) {
            // Fixed source (model_language set in the Models screen).
            translateResolved(sourceText, sourceLanguage, targetLanguage, callback);
            return;
        }
        identifySource(sourceText, detected -> {
            if (detected == null) {
                // Language could not be determined: keep the original text.
                // This is not an environment outage, so no user notice.
                callback.onSuccess(sourceText);
                return;
            }
            translateResolved(sourceText, detected, targetLanguage, callback);
        });
    }

    /** Translates once both languages are known. */
    private void translateResolved(String sourceText, String sourceLanguage,
                                   String targetLanguage, Callback callback) {
        if (sourceLanguage.equals(targetLanguage)) {

            callback.onSuccess(sourceText);
            return;
        }
        if (!isAvailable()) {
            callback.onUnavailable();
            callback.onSuccess(sourceText);
            return;
        }

        final String key = sourceLanguage + ":" + targetLanguage;
        final Translator translator = getOrCreateTranslator(key, sourceText, callback);
        if (translator == null) {
            return; // fallback already delivered by getOrCreateTranslator
        }

        final boolean packDownloaded;
        synchronized (this) {
            packDownloaded = downloaded.contains(key);
        }
        if (packDownloaded) {
            runTranslate(translator, key, sourceText, callback);
        } else {
            translator.downloadModelIfNeeded()
                    .addOnSuccessListener(v -> {
                        synchronized (this) {
                            downloaded.add(key);
                            failed.remove(key);
                        }
                        runTranslate(translator, key, sourceText, callback);
                    })
                    .addOnFailureListener(e -> {
                        synchronized (this) {
                            failed.add(key);
                        }
                        Log.w(TAG, "Language pack download failed for " + key
                                + "; showing original", e);
                        callback.onUnavailable();
                        callback.onSuccess(sourceText);
                    });
        }
    }

    private void identifySource(String text, SourceSink sink) {
        final LanguageIdentifier identifier = getLanguageIdentifier();
        if (identifier == null) {
            sink.onSource(rememberSource(
                    SourceLanguageResolver.pickSource(null, text, sessionSource())));
            return;
        }
        try {
            identifier.identifyLanguage(text)
                    .addOnSuccessListener(tag -> {
                        String picked = SourceLanguageResolver.pickSource(
                                tag, text, sessionSource());
                        Log.d(TAG, "Language ID: '" + tag + "' -> source " + picked);
                        sink.onSource(rememberSource(picked));
                    })
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "Language identification failed; using script detection", e);
                        sink.onSource(rememberSource(
                                SourceLanguageResolver.pickSource(null, text, sessionSource())));
                    });
        } catch (Throwable t) {
            Log.w(TAG, "Language identification threw; using script detection", t);
            sink.onSource(rememberSource(
                    SourceLanguageResolver.pickSource(null, text, sessionSource())));
        }
    }

    /** Returns the cached identifier, or null when ML Kit Language ID is unusable. */
    private synchronized LanguageIdentifier getLanguageIdentifier() {
        if (languageIdentifier != null || languageIdUnavailable) return languageIdentifier;
        try {
            languageIdentifier = LanguageIdentification.getClient();
        } catch (Throwable t) {
            languageIdUnavailable = true;
            Log.w(TAG, "ML Kit language identification unavailable; "
                    + "falling back to script detection", t);
        }
        return languageIdentifier;
    }

    private synchronized String sessionSource() {
        return sessionSourceLanguage;
    }

    /** Remembers a supported detection as the session language; returns it. */
    private synchronized String rememberSource(String code) {
        String supported = languageConstant(code);
        if (supported != null) sessionSourceLanguage = supported;
        return supported;
    }

    /** Returns the cached/created translator, or null after delivering the fallback. */
    private Translator getOrCreateTranslator(String key, String sourceText, Callback callback) {
        synchronized (this) {
            if (failed.contains(key)) {
                callback.onUnavailable();
                callback.onSuccess(sourceText);
                return null;
            }
            Translator existing = translators.get(key);
            if (existing != null) return existing;

            try {
                String srcConst = languageConstant(key.substring(0, key.indexOf(':')));
                String tgtConst = languageConstant(key.substring(key.indexOf(':') + 1));
                if (srcConst == null || tgtConst == null) {
                    callback.onUnavailable();
                    callback.onSuccess(sourceText);
                    return null;
                }
                TranslatorOptions options = new TranslatorOptions.Builder()
                        .setSourceLanguage(srcConst)
                        .setTargetLanguage(tgtConst)
                        .build();
                Translator created = Translation.getClient(options);
                translators.put(key, created);
                return created;
            } catch (Throwable t) {
                Log.w(TAG, "Failed to create translator for " + key + "; showing original", t);
                failed.add(key);
                callback.onUnavailable();
                callback.onSuccess(sourceText);
                return null;
            }
        }
    }

    private void runTranslate(Translator translator, String key, String sourceText,
                              Callback callback) {
        translator.translate(sourceText)
                .addOnSuccessListener(callback::onSuccess)
                .addOnFailureListener(e -> {
                    Log.w(TAG, "Translation failed for " + key + "; showing original", e);
                    callback.onUnavailable();
                    callback.onSuccess(sourceText);
                });
    }

    @Override
    public void cancelAll() {
        // ML Kit Tasks are not cancellable; stale results are dropped by the
        // service's session generation before they reach any UI. We do close
        // every translator (the documented ML Kit cleanup) so a long-lived
        // process does not accumulate per-session native resources.
        synchronized (this) {
            if (probe != null) {
                try {
                    probe.close();
                } catch (Throwable ignored) {
                }
                probe = null;
            }
            for (Translator t : translators.values()) {
                try {
                    t.close();
                } catch (Throwable ignored) {
                }
            }
            translators.clear();
            downloaded.clear();
            failed.clear();
            if (languageIdentifier != null) {
                try {
                    languageIdentifier.close();
                } catch (Throwable ignored) {
                }
                languageIdentifier = null;
            }
            languageIdUnavailable = false;
            sessionSourceLanguage = null;
        }
    }
}
