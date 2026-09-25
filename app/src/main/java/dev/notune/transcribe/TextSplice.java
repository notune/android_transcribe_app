package dev.notune.transcribe;

/**
 * Puts dictated text into a field's text at the cursor (or over the
 * selection), the way typing would, adding a space where the dictation would
 * otherwise glue onto a neighbouring word. Pure Java, so it is unit-tested.
 */
final class TextSplice {

    final String text;
    /** Cursor position after the inserted text. */
    final int cursor;

    private TextSplice(String text, int cursor) {
        this.text = text;
        this.cursor = cursor;
    }

    /**
     * @param existing  the field's current text ("" when empty or showing its hint)
     * @param selStart  selection start, or -1 when unknown (then: end of text)
     * @param selEnd    selection end, or -1 when unknown
     * @param dictation the text to insert
     */
    static TextSplice at(String existing, int selStart, int selEnd, String dictation) {
        if (existing == null) existing = "";
        int len = existing.length();
        int start = selStart < 0 ? len : Math.min(selStart, len);
        int end = selEnd < 0 ? start : Math.min(selEnd, len);
        if (end < start) {
            int t = start;
            start = end;
            end = t;
        }
        String insert = dictation.trim();
        if (start > 0 && !Character.isWhitespace(existing.charAt(start - 1))
                && !startsWithPunctuation(insert)) {
            insert = " " + insert;
        }
        if (end < len && !Character.isWhitespace(existing.charAt(end))
                && !startsWithPunctuation(existing.substring(end))) {
            insert = insert + " ";
        }
        String result = existing.substring(0, start) + insert + existing.substring(end);
        return new TextSplice(result, start + insert.length());
    }

    private static boolean startsWithPunctuation(String s) {
        if (s.isEmpty()) return false;
        char c = s.charAt(0);
        return ".,;:!?)]}".indexOf(c) >= 0;
    }
}
