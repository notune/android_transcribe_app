package dev.notune.transcribe;

/**
 * Decides how dictated text joins the field at the cursor.
 *
 * <p>Pure: there is deliberately no {@code android.*} import here, so every rule is
 * unit-testable on a plain JVM with no device, no emulator and no speech model. The
 * Android side does nothing but read two strings and one integer, call {@link
 * #fit}, and commit the result.
 *
 * <p>The core rule: the dictated text joins the field the way a careful typist would
 * type it at that exact spot. It starts a new sentence only where the text before the
 * cursor ends one, it ends a sentence only where the text after the cursor does not
 * continue one, and it never changes a character that was already in the field.
 *
 * <p>Exactly three things about the model's output may change: the case of its first
 * letter, whitespace at its two ends, and the removal of one terminal punctuation mark.
 * No word is ever changed, no other letter is ever changed, no punctuation is ever
 * added, nothing is ever reordered.
 */
public final class TextFitter {

    private TextFitter() { }

    /** How much sentence logic a field gets. */
    public enum FieldKind {
        /** Ordinary text: all four steps apply. */
        PROSE,
        /** Number, phone, date-time, URI, e-mail and filter: no spaces or case change. */
        PLAIN,
        /** Search text: word spacing, no case change, and no final full stop. */
        SEARCH,
        /** Masked: the text is inserted exactly as the model produced it. */
        PASSWORD
    }

    /**
     * Copies of {@code android.text.TextUtils.CAP_MODE_*}, so this class needs no
     * Android types. The caller passes {@code InputConnection.getCursorCapsMode}'s
     * return value straight through.
     */
    public static final int CAP_MODE_CHARACTERS = 0x00001000;
    public static final int CAP_MODE_WORDS      = 0x00002000;
    public static final int CAP_MODE_SENTENCES  = 0x00004000;
    private static final int CAP_MODE_ANY =
            CAP_MODE_CHARACTERS | CAP_MODE_WORDS | CAP_MODE_SENTENCES;

    /** The exact string to insert, split so a caller can log or assert on the parts. */
    public static final class Fit {
        public final String prefix;
        public final String text;
        public final String suffix;

        Fit(String prefix, String text, String suffix) {
            this.prefix = prefix;
            this.text = text;
            this.suffix = suffix;
        }

        /** The single string handed to {@code commitText}. */
        public String inserted() {
            return prefix + text + suffix;
        }

        public boolean isEmpty() {
            return prefix.isEmpty() && text.isEmpty() && suffix.isEmpty();
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Fit)) return false;
            Fit f = (Fit) o;
            return prefix.equals(f.prefix) && text.equals(f.text) && suffix.equals(f.suffix);
        }

        @Override public int hashCode() {
            return (prefix.hashCode() * 31 + text.hashCode()) * 31 + suffix.hashCode();
        }

        @Override public String toString() {
            return "Fit[" + prefix + "|" + text + "|" + suffix + "]";
        }
    }

    private static final Fit NOTHING = new Fit("", "", "");

    // Context classes for the first letter.
    static final int CTX_SENTENCE_START = 0;
    static final int CTX_UNKNOWN = 1;
    static final int CTX_CONTINUATION = 2;

    /**
     * @param spoken  the model's text for this piece. Trimmed here; empty inserts nothing.
     * @param before  up to 64 characters before the cursor, or {@code null} when the
     *                input connection did not answer.
     * @param after   up to 16 characters after the cursor, or {@code null}.
     * @param kind    the field kind, from {@code EditorInfo.inputType} and {@code imeOptions}.
     * @param capsMode {@code getCursorCapsMode}'s return value. A vote for "capital" only:
     *                it is never decisive, because it returns zero both for "not a sentence
     *                start" and for "the connection is dead". It can only keep a capital the
     *                model produced; it can never introduce one.
     */
    public static Fit fit(String spoken, CharSequence before, CharSequence after,
                          FieldKind kind, int capsMode) {
        if (spoken == null) return NOTHING;
        String t = spoken.trim();
        if (t.isEmpty()) return NOTHING;

        // Field kind is decided before the connection is consulted: it comes from
        // EditorInfo, which is held locally, so it is known even when the field's
        // contents cannot be read. A masked field must not receive a trailing space
        // just because getTextBeforeCursor returned null.
        if (kind == FieldKind.PASSWORD) {
            return new Fit("", t, "");
        }
        if (kind == FieldKind.PLAIN) {
            if (t.endsWith(".")) t = t.substring(0, t.length() - 1);
            return new Fit("", t, "");
        }
        if (kind == FieldKind.SEARCH) {
            if (t.endsWith(".")) t = t.substring(0, t.length() - 1);
            String prefix = "";
            if (before != null && before.length() > 0) {
                char last = before.charAt(before.length() - 1);
                if (!isSpaceLike(last) && !isOpenerAt(before, before.length() - 1)) prefix = " ";
            }
            return new Fit(prefix, t, "");
        }

        // Prose, connection dead: today's exact behaviour. No guess at a context that
        // could not be read.
        if (before == null) {
            return new Fit("", t, " ");
        }

        // Step 1 — space before.
        String prefix = "";
        if (before.length() > 0) {
            char last = before.charAt(before.length() - 1);
            if (!isSpaceLike(last) && !isOpenerAt(before, before.length() - 1)) {
                prefix = " ";
            }
        }

        // Step 2 — case of the first letter.
        String b = trimTrailingSpacesAndTabs(before);
        int context = classify(b, capsMode);
        t = applyLeadingCapital(t, context);

        // Step 3 — terminal punctuation.
        String a = trimLeadingSpacesAndTabs(after);
        if (endsWithTerminator(t) && !a.isEmpty()) {
            char first = a.charAt(0);
            if (Character.isLowerCase(first) || isFollowingPunctuation(first)) {
                t = t.substring(0, t.length() - 1);
            }
        }

        // Use the original following text so existing whitespace is preserved.
        String suffix = "";
        if (after == null || after.length() == 0) {
            suffix = " ";
        } else {
            int cp = Character.codePointAt(after, 0);
            if (Character.isLetter(cp) || Character.isDigit(cp) || isOpenerAt(after, 0)) {
                suffix = " ";
            }
        }

        return new Fit(prefix, t, suffix);
    }

    // -----------------------------------------------------------------------------
    // fitter rule 3 — the leading-capital change (revertible on its own)
    // -----------------------------------------------------------------------------

    /**
     * Lowercases the first letter of a piece that lands in the middle of a sentence.
     *
     * <p>Only in a continuation context, only when the first character is an uppercase
     * letter, only when the first word carries no other uppercase letter, and never for
     * the English pronoun {@code I} or its contractions. The app never uppercases
     * anything.
     *
     * <p>The accepted loss: a piece that opens with a single-capital proper noun
     * ({@code John}, {@code Moscow}) is lowercased. Pieces opening with a common word
     * outnumber those many times over, and a name dictionary is dead weight. Acronyms
     * ({@code NASA}), camel case ({@code iPhone}) and {@code I} are protected.
     */
    static String applyLeadingCapital(String t, int context) {
        if (context != CTX_CONTINUATION) return t;
        if (t.isEmpty()) return t;

        int cp = t.codePointAt(0);
        if (!Character.isUpperCase(cp)) return t;

        String firstWord = firstWord(t);
        if (firstWord.isEmpty()) return t;
        if (isProtectedPronoun(firstWord)) return t;
        if (hasFurtherUpperCase(firstWord)) return t;

        int width = Character.charCount(cp);
        int lower = Character.toLowerCase(cp);   // locale-independent, one code point
        return new String(Character.toChars(lower)) + t.substring(width);
    }

    /** The leading run of letters, digits and apostrophes, so {@code I'm} stays one word. */
    private static String firstWord(String t) {
        int i = 0;
        while (i < t.length()) {
            int cp = t.codePointAt(i);
            if (!Character.isLetterOrDigit(cp) && cp != '\'' && cp != '’') break;
            i += Character.charCount(cp);
        }
        return t.substring(0, i);
    }

    private static boolean isProtectedPronoun(String word) {
        String w = word.replace('’', '\'');
        return w.equals("I") || w.equals("I'm") || w.equals("I'll")
                || w.equals("I've") || w.equals("I'd");
    }

    private static boolean hasFurtherUpperCase(String word) {
        int i = Character.charCount(word.codePointAt(0));
        while (i < word.length()) {
            int cp = word.codePointAt(i);
            if (Character.isUpperCase(cp)) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    // -----------------------------------------------------------------------------
    // Context classification
    // -----------------------------------------------------------------------------

    static int classify(String b, int capsMode) {
        // Trailing openers are not context: "He said (" classifies as "He said",
        // and "\n(" classifies as a newline.
        String x = stripTrailingOpeners(b);

        if (x.isEmpty()) return CTX_SENTENCE_START;

        char last = x.charAt(x.length() - 1);
        if (last == '\n' || last == '\r') return CTX_SENTENCE_START;

        String withoutClosers = stripTrailingClosers(x);
        if (!withoutClosers.isEmpty() && endsWithTerminator(withoutClosers)) {
            return CTX_SENTENCE_START;
        }

        // The framework's vote. It can only preserve a capital, never create one.
        if ((capsMode & CAP_MODE_ANY) != 0) return CTX_SENTENCE_START;

        if (last == ':' || last == '-' || last == '–' || last == '—') {
            return CTX_UNKNOWN;
        }
        return CTX_CONTINUATION;
    }

    // -----------------------------------------------------------------------------
    // Character classes
    // -----------------------------------------------------------------------------

    private static boolean isSpaceLike(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    static boolean endsWithTerminator(String s) {
        if (s.isEmpty()) return false;
        char c = s.charAt(s.length() - 1);
        return c == '.' || c == '!' || c == '?' || c == '…';
    }

    /** Punctuation already present that may follow the cursor. */
    private static boolean isFollowingPunctuation(char c) {
        return ".,;:!?)]}»”\"".indexOf(c) >= 0;
    }

    private static boolean isCloser(char c) {
        return ")]}»”’\"'".indexOf(c) >= 0;
    }

    /**
     * {@code (} {@code [} {@code &#123;} {@code &#171;} {@code &#8222;} {@code &#8220;}
     * {@code &#8216;} always; {@code "} and {@code '} only when the character before
     * them is whitespace or nothing, so the apostrophe in {@code don't} is not an opener.
     */
    private static boolean isOpenerAt(CharSequence s, int i) {
        if (s == null || i < 0 || i >= s.length()) return false;
        char c = s.charAt(i);
        if ("([{«„“‘".indexOf(c) >= 0) return true;
        if (c == '"' || c == '\'') {
            if (i == 0) return true;
            return isSpaceLike(s.charAt(i - 1));
        }
        return false;
    }

    private static String stripTrailingOpeners(String s) {
        String x = s;
        while (true) {
            String y = trimTrailingSpacesAndTabs(x);
            if (!y.isEmpty() && isOpenerAt(y, y.length() - 1)) {
                x = y.substring(0, y.length() - 1);
                continue;
            }
            return y;
        }
    }

    private static String stripTrailingClosers(String s) {
        int end = s.length();
        while (end > 0 && isCloser(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }

    /** Trailing spaces and tabs go; newlines stay, because a newline is context. */
    static String trimTrailingSpacesAndTabs(CharSequence s) {
        if (s == null) return "";
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (c == ' ' || c == '\t') end--; else break;
        }
        return s.subSequence(0, end).toString();
    }

    static String trimLeadingSpacesAndTabs(CharSequence s) {
        if (s == null) return "";
        int start = 0;
        while (start < s.length()) {
            char c = s.charAt(start);
            if (c == ' ' || c == '\t') start++; else break;
        }
        return s.subSequence(start, s.length()).toString();
    }
}
