package dev.notune.transcribe;

/**
 * Plug-in point for live-subtitle text translation. A
 * translator receives the <em>finalized</em> source text of one subtitle
 * segment and delivers either the translation or — by calling
 * {@link Callback#onSuccess(String)} with the source text — a fallback that
 * keeps the original on screen.
 *
 * <p>Contract:</p>
 * <ul>
 *   <li>Implementations may be called from any thread; the service serializes
 *       requests and applies results on the main thread in strict FIFO order,
 *       so slow or failing translations never reorder captions.</li>
 *   <li>{@code sourceLanguage}/{@code targetLanguage} are ML Kit language
 *       codes. A {@code null} source means "auto-detect from the text" (the
 *       implementation keeps the source text if it cannot tell); a
 *       {@code null} target means no translation (source text).</li>
 *   <li>Never throw: route every failure through
 *       {@link Callback#onFailure(Throwable)} (or the source-text fallback).</li>
 * </ul>
 */
public interface SubtitleTranslator {

    /** Translates one finalized subtitle segment. */
    void translate(String sourceText, String sourceLanguage, String targetLanguage,
                   Callback callback);

    /**
     * Best-effort abort of in-flight work. Results delivered after this call
     * are dropped by the caller's session generation; no UI is touched.
     */
    void cancelAll();

    /** Result sink for one translation request. */
    interface Callback {
        void onSuccess(String translatedText);

        void onFailure(Throwable error);

        /**
         * Called (possibly repeatedly) when translation is unavailable or a
         * request fell back to the source text because of an environment
         * problem (no Play Services, missing language pack, engine error).
         * The caller typically uses this to inform the user once, without
         * spamming on every segment.
         */
        default void onUnavailable() {
        }
    }
}
