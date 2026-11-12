package dev.notune.transcribe;

import com.google.mlkit.nl.translate.TranslateLanguage;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Supported live-subtitle translation targets, sourced directly from ML Kit. */
public final class SubtitleTranslationTargets {
    /** Marker value meaning no translation; keep the original speech. */
    public static final String AUTO = "auto";

    private SubtitleTranslationTargets() {}

    /**
     * Returns Auto followed by every language supported by the installed
     * ML Kit Translation SDK, sorted by localized language name.
     */
    public static List<String> getTags() {
        List<String> tags = new ArrayList<>();
        tags.add(AUTO);
        tags.addAll(TranslateLanguage.getAllLanguages());

        final Collator collator = Collator.getInstance();
        Collections.sort(tags.subList(1, tags.size()), new Comparator<String>() {
            @Override
            public int compare(String left, String right) {
                int byName = collator.compare(
                        Locale.forLanguageTag(left).getDisplayName(),
                        Locale.forLanguageTag(right).getDisplayName());
                return byName != 0 ? byName : left.compareTo(right);
            }
        });
        return tags;
    }

    /** Whether a stored tag is supported by ML Kit (or is Auto). */
    public static boolean isValid(String tag) {
        return tag != null && (AUTO.equalsIgnoreCase(tag.trim()) || mlKitCode(tag) != null);
    }

    /** Returns ML Kit's canonical language code for a BCP-47 tag, or null. */
    public static String mlKitCode(String tag) {
        String primary = primaryTag(tag);
        if (primary == null) return null;
        try {
            return TranslateLanguage.fromLanguageTag(primary);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /** Primary subtag of a BCP-47 tag, lowercased; null for Auto/blank. */
    public static String primaryTag(String tag) {
        if (tag == null) return null;
        String value = tag.trim();
        if (value.isEmpty() || value.equalsIgnoreCase(AUTO)) return null;
        int dash = value.indexOf('-');
        return (dash < 0 ? value : value.substring(0, dash)).toLowerCase(Locale.ROOT);
    }
}
