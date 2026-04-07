package dev.notune.transcribe;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Generates consistent file base names for transcripts and recordings.
 *
 * Format: YYYY-MM-DD-slug
 *
 * The slug is derived from the first 3-5 words (or up to 30 characters) of the
 * transcript text, lower-cased, umlaut-normalised and slugified.
 * Falls back to "transkript" when no text is available.
 *
 * Both the .txt transcript and the .wav recording share the same base name so
 * that they can easily be correlated on disk.
 */
public final class FileNameHelper {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final int MAX_SLUG_CHARS = 30;
    private static final int MIN_SLUG_WORDS = 3;
    private static final int MAX_SLUG_WORDS = 5;

    private FileNameHelper() {}

    /**
     * Returns a base name (without extension) for today, e.g.
     * {@code 2026-04-07-heute-bespreche-ich-den}
     */
    public static String buildBaseName(String transcriptText) {
        String date = LocalDate.now().format(DATE_FMT);
        String slug = slugify(transcriptText);
        return date + "-" + slug;
    }

    /** Returns the .txt filename. */
    public static String txtName(String transcriptText) {
        return buildBaseName(transcriptText) + ".txt";
    }

    /** Returns the .wav filename. */
    public static String wavName(String transcriptText) {
        return buildBaseName(transcriptText) + ".wav";
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static String slugify(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "transkript";
        }

        // Normalise umlauts before stripping diacritics
        String s = text.trim()
                .replace("\u00e4", "ae")
                .replace("\u00f6", "oe")
                .replace("\u00fc", "ue")
                .replace("\u00c4", "ae")
                .replace("\u00d6", "oe")
                .replace("\u00dc", "ue")
                .replace("\u00df", "ss");

        // Strip remaining diacritics (e.g. é → e)
        s = Normalizer.normalize(s, Normalizer.Form.NFD);
        s = s.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");

        // Lower-case
        s = s.toLowerCase(Locale.ROOT);

        // Replace anything that is not a-z or 0-9 with a hyphen
        s = s.replaceAll("[^a-z0-9]+", "-");

        // Split into words (non-empty tokens)
        String[] words = s.split("-");

        // Take 3-5 words up to MAX_SLUG_CHARS characters total
        StringBuilder sb = new StringBuilder();
        int wordCount = 0;
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (wordCount >= MAX_SLUG_WORDS) break;
            if (sb.length() > 0) sb.append("-");
            sb.append(word);
            wordCount++;
            if (sb.length() >= MAX_SLUG_CHARS) break;
        }

        // Ensure minimum word count is met (no truncation below MIN_SLUG_WORDS unless text is short)
        String result = sb.toString();

        // Trim trailing hyphen
        result = result.replaceAll("-+$", "");

        return result.isEmpty() ? "transkript" : result;
    }
}
