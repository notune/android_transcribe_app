package dev.notune.transcribe;

/** Joins independently transcribed pieces without ending a sentence at every pause. */
public final class PieceJoiner {
    public static final float DEFAULT_SENTENCE_PAUSE_SECONDS = 3.0f;

    private String heldTail = "";
    private boolean hasPiece = false;

    public String join(String raw, float pauseBeforeSeconds, float sentencePauseSeconds) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) return "";

        boolean continuation = hasPiece && pauseBeforeSeconds < sentencePauseSeconds;
        String prefix = "";
        if (!heldTail.isEmpty()) {
            prefix = continuation ? " " : heldTail;
            heldTail = "";
        }
        if (continuation) text = lowerLeadingCapital(text);

        char last = text.charAt(text.length() - 1);
        if (last == '.' || last == '!' || last == '?' || last == '…') {
            heldTail = last + " ";
            text = text.substring(0, text.length() - 1);
        } else {
            text += " ";
        }
        hasPiece = true;
        return prefix + text;
    }

    public String finish() {
        String tail = heldTail;
        heldTail = "";
        return tail;
    }

    private static String lowerLeadingCapital(String text) {
        int first = text.codePointAt(0);
        if (!Character.isUpperCase(first)) return text;

        int end = 0;
        boolean furtherUpper = false;
        while (end < text.length()) {
            int cp = text.codePointAt(end);
            if (!Character.isLetterOrDigit(cp) && cp != '\'' && cp != '’') break;
            if (end > 0 && Character.isUpperCase(cp)) furtherUpper = true;
            end += Character.charCount(cp);
        }
        String firstWord = text.substring(0, end);
        if (furtherUpper || firstWord.equals("I") || firstWord.startsWith("I'")
                || firstWord.startsWith("I’")) return text;

        return new String(Character.toChars(Character.toLowerCase(first)))
                + text.substring(Character.charCount(first));
    }
}
