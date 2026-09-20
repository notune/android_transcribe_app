package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import dev.notune.transcribe.TextFitter.FieldKind;
import dev.notune.transcribe.TextFitter.Fit;

/**
 * The decision table of PRODUCT-DECISION section 2, row by row, each row with an
 * opposite case that must give a different answer, plus the "what the app must never
 * do" invariants.
 *
 * <p>Deliberately case-independent. Every assertion here holds whether or not fitter
 * rule 3 (the leading-capital change) is present, so reverting rule 3 leaves this file
 * green. Rule 3's own rows, including the capitalised rows of the section 2 table and
 * the caps-mode vote, live in {@link TextFitterCapitalTest}, which is deleted with it.
 */
public class TextFitterTest {

    private static final int NO_CAPS = 0;

    /** What the field holds after the commit: the only write is one commitText. */
    private static String field(String before, String after, Fit fit) {
        return before + fit.inserted() + after;
    }

    private static Fit prose(String spoken, String before, String after) {
        return TextFitter.fit(spoken, before, after, FieldKind.PROSE, NO_CAPS);
    }

    /**
     * Must-never rule 1: no character that was in the field is modified, deleted or
     * moved. Checked positionally, not only by concatenation.
     */
    private static void assertSurroundingsIntact(String before, String after, Fit fit) {
        String result = field(before, after, fit);
        assertTrue("text before the cursor changed: " + result, result.startsWith(before));
        assertTrue("text after the cursor changed: " + result, result.endsWith(after));
        assertEquals("length changed by something other than the insertion",
                before.length() + fit.inserted().length() + after.length(), result.length());
    }

    // ---------------------------------------------------------------- the table

    @Test public void emptyField() {
        Fit f = prose("And then we go home.", "", "");
        assertEquals("And then we go home. ", f.inserted());
        assertSurroundingsIntact("", "", f);
        // Opposite: the same text mid-sentence loses the mark and the trailing space.
        assertNotEquals(f.inserted(), prose("And then we go home.", "", " and rest.").inserted());
    }

    @Test public void afterACommaAndSpaceNoSecondSpaceIsAdded() {
        Fit f = prose("and then we go home.", "Hello, ", "");
        assertEquals("Hello, and then we go home. ", field("Hello, ", "", f));
        assertEquals("", f.prefix);
        // Opposite: without the space the fitter supplies one.
        assertEquals(" ", prose("and then we go home.", "Hello,", "").prefix);
    }

    @Test public void midSentenceInsertionCursorBeforeSpace() {
        // "I think we go home." with the cursor right after "we".
        Fit f = prose("should probably.", "I think we", " go home.");
        assertEquals("I think we should probably go home.",
                field("I think we", " go home.", f));
        assertSurroundingsIntact("I think we", " go home.", f);
    }

    @Test public void midSentenceInsertionCursorAfterSpace() {
        // The same edit with the cursor on the other side of the space: same result,
        // and still no double space.
        Fit f = prose("should probably.", "I think we ", "go home.");
        assertEquals("I think we should probably go home.",
                field("I think we ", "go home.", f));
        assertSurroundingsIntact("I think we ", "go home.", f);
        // Opposite: at the end of the same sentence the full stop survives.
        assertEquals("I think we go home. should probably. ",
                field("I think we go home. ", "",
                        prose("should probably.", "I think we go home. ", "")));
    }

    @Test public void afterALineBreakNoSpaceIsAdded() {
        Fit f = prose("And then we go home.", "First line.\n", "");
        assertEquals("First line.\nAnd then we go home. ", field("First line.\n", "", f));
        assertEquals("", f.prefix);
        // Opposite: mid-line the fitter supplies a space.
        assertEquals(" ", prose("And then we go home.", "First line and", "").prefix);
    }

    @Test public void afterAnOpeningBracketNoSpaceIsAdded() {
        Fit f = prose("and then we go home.", "He said (", "");
        assertEquals("He said (and then we go home. ", field("He said (", "", f));
        assertEquals("", f.prefix);
        // Opposite: with a word instead of the bracket a space is added.
        assertEquals(" ", prose("and then we go home.", "He said", "").prefix);
    }

    @Test public void beforeAClosingBracketTheMarkAndTheSpaceGo() {
        Fit f = prose("and then we go home.", "Note (", ")");
        assertEquals("Note (and then we go home)", field("Note (", ")", f));
        assertSurroundingsIntact("Note (", ")", f);
        // Opposite: with nothing after the cursor the full stop and the space stay.
        assertEquals("Note (and then we go home. ",
                field("Note (", "", prose("and then we go home.", "Note (", "")));
    }

    @Test public void secondDictationStraightAfterTheFirst() {
        Fit f = prose("Then we eat.", "I go home. ", "");
        assertEquals("I go home. Then we eat. ", field("I go home. ", "", f));
        assertEquals("", f.prefix);
    }

    @Test public void afterAColonTheContextIsUnknownSoTheCaseIsUntouched() {
        Fit f = prose("And then we go home.", "Note: ", "");
        assertEquals("Note: And then we go home. ", field("Note: ", "", f));
        // Opposite: without the trailing space a space is supplied.
        assertEquals(" ", prose("And then we go home.", "Note:", "").prefix);
    }

    @Test public void afterADashTheContextIsUnknownSoTheCaseIsUntouched() {
        Fit f = prose("And then we go home.", "Note - ", "");
        assertEquals("Note - And then we go home. ", field("Note - ", "", f));
    }

    @Test public void connectionReturnsNull() {
        Fit f = TextFitter.fit("And then we go home.", null, null, FieldKind.PROSE, NO_CAPS);
        assertEquals("today's exact behaviour", "And then we go home. ", f.inserted());
        // Opposite: a live connection mid-sentence gives a different answer.
        assertNotEquals(f.inserted(),
                prose("And then we go home.", "I think ", " tonight.").inserted());
    }

    @Test public void nullAfterStillGetsATrailingSpace() {
        Fit f = TextFitter.fit("And then.", "Hello. ", null, FieldKind.PROSE, NO_CAPS);
        assertEquals("And then. ", f.inserted());
    }

    @Test public void emptySpokenTextInsertsNothing() {
        assertTrue(prose("", "Hello, ", "").isEmpty());
        assertTrue(prose("   ", "Hello, ", "").isEmpty());
        assertTrue(TextFitter.fit(null, "Hello, ", "", FieldKind.PROSE, NO_CAPS).isEmpty());
        assertEquals("Hello, ", field("Hello, ", "", prose("", "Hello, ", "")));
    }

    // ------------------------------------------------------------- field kinds

    @Test public void passwordFieldGetsTheTextExactly() {
        Fit f = TextFitter.fit("Hunter two.", "abc", "def", FieldKind.PASSWORD, NO_CAPS);
        assertEquals("Hunter two.", f.inserted());
        assertSurroundingsIntact("abc", "def", f);
        // Opposite: the same input in a prose field is changed.
        assertNotEquals(f.inserted(), prose("Hunter two.", "abc", "def").inserted());
    }

    @Test public void passwordFieldWithADeadConnection() {
        // The kind is known from EditorInfo even when the field cannot be read, so a
        // masked field must not pick up the null-connection trailing space.
        Fit f = TextFitter.fit("Hunter two.", null, null, FieldKind.PASSWORD, NO_CAPS);
        assertEquals("Hunter two.", f.inserted());
    }

    @Test public void plainFieldLosesOneTrailingFullStopAndGetsNoSpaces() {
        Fit f = TextFitter.fit("Weather in Moscow.", "", "", FieldKind.PLAIN, NO_CAPS);
        assertEquals("Weather in Moscow", f.inserted());
        // Opposite: prose keeps the mark and adds the space.
        assertEquals("Weather in Moscow. ", prose("Weather in Moscow.", "", "").inserted());
    }

    @Test public void plainFieldNeverChangesCase() {
        Fit f = TextFitter.fit("Moscow.", "weather in ", "", FieldKind.PLAIN, NO_CAPS);
        assertEquals("Moscow", f.inserted());
    }

    @Test public void plainFieldWithNoTrailingMarkIsUntouched() {
        assertEquals("42", TextFitter.fit("42", "", "", FieldKind.PLAIN, NO_CAPS).inserted());
        assertEquals("a@b.com",
                TextFitter.fit("a@b.com", "", "", FieldKind.PLAIN, NO_CAPS).inserted());
    }

    // ----------------------------------------------- what the app must never do

    @Test public void onlyTheFirstLetterTheEdgeSpacesAndOneMarkEverChange() {
        String spoken = "  So we called Dr. Smith about the iPhone 14 Pro, twice!  ";
        Fit f = prose(spoken, "I think ", " and left.");
        String body = f.text;
        String trimmed = spoken.trim();
        assertTrue("more than one character was removed",
                body.length() == trimmed.length() || body.length() == trimmed.length() - 1);
        // Every character but the first is byte-identical to the model's own output.
        assertEquals(trimmed.substring(1, body.length()), body.substring(1));
        assertSurroundingsIntact("I think ", " and left.", f);
    }

    @Test public void noPunctuationIsEverAdded() {
        Fit f = prose("and then we go home", "Hello, ", "");
        assertEquals("no terminal mark is invented", "and then we go home ", f.inserted());
    }

    @Test public void onlyOneTerminalMarkIsEverRemoved() {
        Fit f = prose("really?!", "I think we", " go home.");
        assertEquals("really?", f.text);
    }

    @Test public void numbersAndQuotesInsideTheTextAreNeverNormalised() {
        String spoken = "3.14 is \"pi\", roughly.";
        Fit f = prose(spoken, "He said ", "");
        assertEquals(spoken, f.text);
    }

    @Test public void aTerminalMarkSurvivesWhenTheSentenceDoesNotContinue() {
        // Opposite of the removal rule: an upper-case letter follows the cursor.
        Fit f = prose("We go home.", "I think we should. ", "Then we eat.");
        assertEquals("We go home.", f.text);
        // A letter follows, so the next sentence still gets its separating space.
        assertEquals(" ", f.suffix);
        assertEquals("I think we should. We go home. Then we eat.",
                field("I think we should. ", "Then we eat.", f));
    }

    @Test public void aSelectionIsReplacedAndTheTextOutsideItIsUsed() {
        // With a selection, getTextBeforeCursor/AfterCursor report the text outside it
        // and commitText replaces the selection, so the fitter sees exactly this.
        Fit f = prose("walked away.", "I think we ", " home.");
        assertEquals("I think we walked away home.", field("I think we ", " home.", f));
    }

    // ------------------------------------------- how far the decision can reach

    private static String spaces(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(' ');
        return sb.toString();
    }

    @Test public void theCharacterThatDecidesTheMarkCanBeFarAfterTheCursor() {
        // Forty spaces of indentation, then the sentence continues. The service reads
        // a window of the field, not the whole of it; a window short enough to hold
        // only the spaces would leave a full stop in the middle of the sentence. The
        // fitter itself caps nothing, and this test fails if a cap is put back in.
        String after = spaces(40) + "go home.";
        Fit f = prose("we should probably.", "I think ", after);
        assertEquals("we should probably", f.text);
        assertEquals("no space is added before the indentation", "", f.suffix);
        // Opposite: a capital after the same spaces starts a new sentence, so the
        // mark stays.
        assertEquals("we should probably.",
                prose("we should probably.", "I think ", spaces(40) + "Go home.").text);
    }

    @Test public void aVeryLongReadBeforeTheCursorIsHandledAsOneSentence() {
        StringBuilder sb = new StringBuilder("First sentence. ");
        while (sb.length() < 4000) sb.append("more words and ");
        String before = sb.toString().trim();   // ends in a word, so a space is needed
        Fit f = prose("then we go home.", before, "");
        assertEquals(" ", f.prefix);
        assertEquals("then we go home.", f.text);
        assertSurroundingsIntact(before, "", f);
    }

    @Test public void aNewlineAfterTheCursorKeepsTheMarkAndAddsNoSpace() {
        Fit f = prose("we go home.", "I think ", "\nnext line");
        assertEquals("we go home.", f.text);
        assertEquals("", f.suffix);
    }
}
