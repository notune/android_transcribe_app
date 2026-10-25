package dev.notune.transcribe;

/** Validates a selection snapshot before a live composing edit can replace it. */
final class LiveSelectionSnapshot {
    private LiveSelectionSnapshot() {}

    /** Null means unsafe to replace; an empty string is a valid empty selection. */
    static String capture(int start, int end, CharSequence selected) {
        if (start < 0 || end < 0) return null;
        long length = Math.abs((long) end - start);
        if (length == 0) {
            return selected == null || selected.length() == 0 ? "" : null;
        }
        if (selected == null || selected.length() != length) return null;
        return selected.toString();
    }
}
