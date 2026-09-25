package dev.notune.transcribe;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists the user's custom-word list as a plain-text file in filesDir, one
 * entry per line — the same marker-file pattern the Rust engine reads for every
 * other setting ({@code custom_words}). Whisper models receive the list as their
 * initial prompt, a recognition bias toward these words; models without Whisper
 * support ignore it.
 *
 * This class is the single authority for normalization. Entries are trimmed,
 * inner whitespace is collapsed to a single space, and the list is
 * case-insensitively de-duplicated with first spelling and first position
 * winning. Explicit caps ({@link #MAX_WORDS}, {@link #MAX_ENTRY_LENGTH}) keep a
 * large paste from bloating the prompt the model is handed on every run.
 */
public final class CustomWordsPrefs {
    private static final String FILE_NAME = "custom_words";
    /** Hard cap on list size, so the per-run prompt stays bounded. */
    public static final int MAX_WORDS = 200;
    /** Hard cap on a single entry's length after normalization. */
    public static final int MAX_ENTRY_LENGTH = 60;

    private CustomWordsPrefs() {}

    /** Outcome of an add/edit attempt, so the UI can give specific feedback. */
    public enum AddResult { ADDED, EMPTY, TOO_LONG, DUPLICATE, LIMIT_REACHED }

    /**
     * Normalizes one raw entry: trims it and collapses inner whitespace runs to
     * a single space. Returns null when nothing usable remains. Length is not
     * checked here so callers can report "too long" distinctly from "empty".
     */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim().replaceAll("\\s+", " ");
        return s.isEmpty() ? null : s;
    }

    /** The stored list, in order. Empty if the file is absent or unreadable. */
    public static List<String> getAll(Context ctx) {
        List<String> list = new ArrayList<>();
        File f = new File(ctx.getFilesDir(), FILE_NAME);
        if (!f.exists()) return list;
        try {
            String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            for (String line : content.split("\n")) {
                String s = line.trim();
                if (!s.isEmpty()) list.add(s);
            }
        } catch (IOException ignored) { }
        return list;
    }

    /** Writes the list verbatim (callers pass an already-normalized list). */
    private static void saveAll(Context ctx, List<String> words) {
        File f = new File(ctx.getFilesDir(), FILE_NAME);
        if (words.isEmpty()) {
            f.delete();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String w : words) sb.append(w).append('\n');
        try {
            Files.write(f.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
    }

    /** Adds one entry. De-duplicates case-insensitively; respects the caps. */
    public static AddResult add(Context ctx, String raw) {
        String word = normalize(raw);
        if (word == null) return AddResult.EMPTY;
        if (word.length() > MAX_ENTRY_LENGTH) return AddResult.TOO_LONG;
        List<String> list = getAll(ctx);
        if (containsIgnoreCase(list, word)) return AddResult.DUPLICATE;
        if (list.size() >= MAX_WORDS) return AddResult.LIMIT_REACHED;
        list.add(word);
        saveAll(ctx, list);
        return AddResult.ADDED;
    }

    /** Removes an entry matched case-insensitively. No-op if not present. */
    public static void remove(Context ctx, String word) {
        List<String> list = getAll(ctx);
        if (list.removeIf(w -> w.equalsIgnoreCase(word))) {
            saveAll(ctx, list);
        }
    }

    /**
     * Replaces {@code oldWord} (matched case-insensitively) with a normalized
     * {@code newRaw}, keeping its position. Editing to a value already present
     * elsewhere reports {@link AddResult#DUPLICATE}; editing only the casing of
     * an entry is allowed.
     */
    public static AddResult update(Context ctx, String oldWord, String newRaw) {
        String word = normalize(newRaw);
        if (word == null) return AddResult.EMPTY;
        if (word.length() > MAX_ENTRY_LENGTH) return AddResult.TOO_LONG;
        List<String> list = getAll(ctx);
        int idx = indexOfIgnoreCase(list, oldWord);
        if (idx < 0) return AddResult.EMPTY;
        int existing = indexOfIgnoreCase(list, word);
        if (existing >= 0 && existing != idx) return AddResult.DUPLICATE;
        list.set(idx, word);
        saveAll(ctx, list);
        return AddResult.ADDED;
    }

    /**
     * Imports one entry per line. Returns how many were actually added; blanks,
     * over-long, duplicate and over-limit entries are skipped.
     */
    public static int importBulk(Context ctx, String text) {
        if (text == null) return 0;
        List<String> list = getAll(ctx);
        int added = 0;
        for (String line : text.split("\n")) {
            String word = normalize(line);
            if (word == null || word.length() > MAX_ENTRY_LENGTH) continue;
            if (containsIgnoreCase(list, word)) continue;
            if (list.size() >= MAX_WORDS) break;
            list.add(word);
            added++;
        }
        if (added > 0) saveAll(ctx, list);
        return added;
    }

    private static boolean containsIgnoreCase(List<String> list, String word) {
        return indexOfIgnoreCase(list, word) >= 0;
    }

    private static int indexOfIgnoreCase(List<String> list, String word) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).equalsIgnoreCase(word)) return i;
        }
        return -1;
    }
}
