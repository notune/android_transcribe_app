package dev.notune.transcribe;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Older recovery text and the one current result that automatic delivery may send. */
final class PendingDictationDraft {
    static final String PENDING = "pending";
    static final String ATTEMPTED = "attempted";
    private static final String NONE = "none";

    final String savedText;
    final boolean savedMayAlreadyBeDelivered;
    final String state;
    final String text;
    final String currentText;

    PendingDictationDraft(String state, String text) {
        this("", false, state, text);
    }

    private PendingDictationDraft(
            String savedText, boolean savedMayAlreadyBeDelivered, String state, String text) {
        this.savedText = savedText == null ? "" : savedText;
        this.savedMayAlreadyBeDelivered = savedMayAlreadyBeDelivered;
        this.state = state;
        this.text = text == null ? "" : text;
        this.currentText = this.text;
    }

    PendingDictationDraft stageCurrent(String newText) {
        String older = savedText;
        boolean olderMayBeDelivered = savedMayAlreadyBeDelivered;
        if (hasCurrent()) {
            older = joinResults(older, text);
            olderMayBeDelivered |= currentMayAlreadyBeDelivered();
        }
        return new PendingDictationDraft(older, olderMayBeDelivered, PENDING, newText);
    }

    PendingDictationDraft withState(String newState) {
        return new PendingDictationDraft(
                savedText, savedMayAlreadyBeDelivered, newState, text);
    }

    PendingDictationDraft withoutCurrent() {
        return new PendingDictationDraft(
                savedText, savedMayAlreadyBeDelivered, NONE, "");
    }

    PendingDictationDraft asSingleAttempt() {
        return new PendingDictationDraft(ATTEMPTED, recoveryText());
    }

    boolean hasCurrent() {
        return !NONE.equals(state);
    }

    boolean isEmpty() {
        return savedText.isEmpty() && !hasCurrent();
    }

    String recoveryText() {
        return hasCurrent() ? joinResults(savedText, text) : savedText;
    }

    boolean currentMayAlreadyBeDelivered() {
        return hasCurrent() && ATTEMPTED.equals(state);
    }

    boolean mayAlreadyBeDelivered() {
        return savedMayAlreadyBeDelivered || currentMayAlreadyBeDelivered();
    }

    boolean sameAs(PendingDictationDraft other) {
        return other != null
                && savedText.equals(other.savedText)
                && savedMayAlreadyBeDelivered == other.savedMayAlreadyBeDelivered
                && state.equals(other.state)
                && text.equals(other.text);
    }

    byte[] encode() {
        String body = "NOTUNE2\n"
                + (savedMayAlreadyBeDelivered ? "1" : "0") + "\n"
                + encodeText(savedText) + "\n"
                + state + "\n"
                + encodeText(text);
        return body.getBytes(StandardCharsets.UTF_8);
    }

    static PendingDictationDraft decode(byte[] bytes) {
        try {
            String body = new String(bytes, StandardCharsets.UTF_8);
            if (body.startsWith("NOTUNE1\n")) {
                String[] parts = body.split("\\n", 3);
                if (parts.length != 3 || !validActiveState(parts[1])) return null;
                return new PendingDictationDraft(parts[1], decodeText(parts[2]));
            }

            String[] parts = body.split("\\n", -1);
            if (parts.length != 5 || !"NOTUNE2".equals(parts[0])
                    || !("0".equals(parts[1]) || "1".equals(parts[1]))
                    || !validState(parts[3])) {
                return null;
            }
            String saved = decodeText(parts[2]);
            String current = decodeText(parts[4]);
            if (NONE.equals(parts[3]) && !current.isEmpty()) return null;
            return new PendingDictationDraft(saved, "1".equals(parts[1]), parts[3], current);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String encodeText(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeText(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String joinResults(String older, String newer) {
        if (older.isEmpty() || newer.isEmpty()
                || Character.isWhitespace(older.charAt(older.length() - 1))
                || Character.isWhitespace(newer.charAt(0))) {
            return older + newer;
        }
        return older + " " + newer;
    }

    private static boolean validActiveState(String value) {
        return PENDING.equals(value) || ATTEMPTED.equals(value);
    }

    private static boolean validState(String value) {
        return NONE.equals(value) || validActiveState(value);
    }
}
