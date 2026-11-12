package dev.notune.transcribe;

/** Resolves source languages supported by the installed ML Kit translator. */
public final class SourceLanguageResolver {
    private SourceLanguageResolver() {}

    public static final String UNDETERMINED = "und";

    /** Resolves a supported source code from the selected speech-model tag. */
    public static String resolve(String text, String modelLanguageTag) {
        String fromModel = primaryCode(modelLanguageTag);
        return fromModel != null ? fromModel : detectFromText(text);
    }

    /** Normalizes a model-language tag to an ML Kit language code. */
    public static String primaryCode(String tag) {
        return SubtitleTranslationTargets.mlKitCode(tag);
    }

    /** Accepts an identified language only when ML Kit Translation supports it. */
    public static String identifiedCode(String identifiedTag) {
        if (identifiedTag == null || identifiedTag.trim().isEmpty()
                || UNDETERMINED.equalsIgnoreCase(identifiedTag.trim())) {
            return null;
        }
        return SubtitleTranslationTargets.mlKitCode(identifiedTag);
    }

    /**
     * Prefer ML Kit Language ID, then script detection, then the last
     * confidently detected supported language in this session.
     */
    public static String pickSource(String identifiedTag, String text, String sessionLanguage) {
        String identified = identifiedCode(identifiedTag);
        if (identified != null) return identified;

        String heuristic = detectFromText(text);
        if (heuristic != null) return heuristic;

        return primaryCode(sessionLanguage);
    }

    /** Detects Persian text when Language ID cannot confidently identify it. */
    public static String detectFromText(String text) {
        if (text == null || text.isEmpty()) return null;
        for (int i = 0; i < text.length();) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if ((codePoint >= 0x0600 && codePoint <= 0x06FF)
                    || (codePoint >= 0x0750 && codePoint <= 0x077F)
                    || (codePoint >= 0x08A0 && codePoint <= 0x08FF)) {
                return SubtitleTranslationTargets.mlKitCode("fa");
            }
        }
        return null;
    }
}
