package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import dev.notune.transcribe.TextFitter.FieldKind;
import dev.notune.transcribe.TextFitter.Fit;

/**
 * Fitter rule 3 only: the leading-capital change.
 *
 * <p>This file is the evidence for the one transformation that can turn
 * "Sarah called" into "sarah called". It is deleted together with
 * {@code TextFitter.applyLeadingCapital} and its single call site if the rule is
 * reverted; {@link TextFitterTest} then still passes unchanged.
 *
 * <p>The tests under "proper nouns that must never be corrupted" exist to fail. They
 * are the ones that go red if the protections are weakened, and they were run against a
 * deliberately weakened fitter to prove they can.
 *
 * <p>Cyrillic is written as escapes so the result cannot depend on the compiler's
 * source encoding. The readable text is in each comment.
 */
public class TextFitterCapitalTest {

    private static final int NO_CAPS = 0;

    private static Fit prose(String spoken, String before, String after) {
        return TextFitter.fit(spoken, before, after, FieldKind.PROSE, NO_CAPS);
    }

    private static String field(String before, String after, Fit fit) {
        return before + fit.inserted() + after;
    }

    // ------------------------------------- the capitalised rows of the section 2 table

    @Test public void afterACommaTheCapitalIsLowered() {
        Fit f = prose("And then we go home.", "Hello, ", "");
        assertEquals("Hello, and then we go home. ", field("Hello, ", "", f));
        // Opposite: after a full stop the same input keeps its capital.
        assertEquals("Hello. And then we go home. ",
                field("Hello. ", "", prose("And then we go home.", "Hello. ", "")));
    }

    @Test public void midSentenceTheCapitalIsLowered() {
        Fit f = prose("Should probably.", "I think we", " go home.");
        assertEquals("I think we should probably go home.",
                field("I think we", " go home.", f));
        // Opposite: at a sentence start the same input keeps its capital.
        assertEquals("I think we go home. Should probably. ",
                field("I think we go home. ", "",
                        prose("Should probably.", "I think we go home. ", "")));
    }

    @Test public void afterAnOpeningBracketTheOpenerIsNotTheContext() {
        assertEquals("He said (and then we go home. ",
                field("He said (", "", prose("And then we go home.", "He said (", "")));
        // Opposite: a newline behind the opener is a sentence start.
        assertEquals("First line.\n(And then we go home. ",
                field("First line.\n(", "", prose("And then we go home.", "First line.\n(", "")));
    }

    @Test public void beforeAClosingBracket() {
        assertEquals("Note (and then we go home)",
                field("Note (", ")", prose("And then we go home.", "Note (", ")")));
    }

    @Test public void afterAColonNothingIsDecided() {
        assertEquals("Note: And then we go home. ",
                field("Note: ", "", prose("And then we go home.", "Note: ", "")));
        // Opposite: after a comma it is a continuation.
        assertEquals("Note, and then we go home. ",
                field("Note, ", "", prose("And then we go home.", "Note, ", "")));
    }

    @Test public void russianFollowsTheSameRule() {
        // before: "Привет, " ("Privet, ")
        // spoken: "И потом мы "
        //       + "идём домой."
        String before = "Привет, ";
        String spoken = "И потом мы "
                + "идём домой.";
        String lowered = "и потом мы "
                + "идём домой.";
        assertEquals(before + lowered + " ", field(before, "", prose(spoken, before, "")));
        // Opposite: at a sentence start the Cyrillic capital is kept.
        assertEquals(spoken + " ", prose(spoken, "", "").inserted());
    }

    @Test public void theJoinBetweenStreamedPiecesUsesTheSameRule() {
        // Piece one ended with a full stop: piece two keeps its capital.
        assertEquals("Go home. ", prose("Go home.", "I think we should. ", "").inserted());
        // Piece one ended with no mark: piece two is lowered.
        assertEquals(" go home. ", prose("Go home.", "I think we should", "").inserted());
    }

    // --------------------------- proper nouns that must never be corrupted (exist to fail)

    @Test public void anAcronymIsNeverLowered() {
        Fit f = prose("NASA people.", "we met ", "");
        assertEquals("we met NASA people. ", field("we met ", "", f));
    }

    @Test public void camelCaseIsNeverLowered() {
        Fit f = prose("IPhone sales rose.", "he said ", "");
        assertEquals("he said IPhone sales rose. ", field("he said ", "", f));
        // And a genuinely lower-case-initial word is untouched by definition.
        assertEquals("he said iPhone sales rose. ",
                field("he said ", "", prose("iPhone sales rose.", "he said ", "")));
    }

    @Test public void theEnglishPronounIIsNeverLowered() {
        assertEquals("and I think so. ", field("and ", "", prose("I think so.", "and ", "")));
        assertEquals("and I'm sure. ", field("and ", "", prose("I'm sure.", "and ", "")));
        assertEquals("and I'll go. ", field("and ", "", prose("I'll go.", "and ", "")));
        assertEquals("and I've gone. ", field("and ", "", prose("I've gone.", "and ", "")));
        assertEquals("and I'd go. ", field("and ", "", prose("I'd go.", "and ", "")));
        // The curly apostrophe the model may produce is the same word.
        assertEquals("and I’m sure. ",
                field("and ", "", prose("I’m sure.", "and ", "")));
    }

    @Test public void aProperNounThatIsNotTheFirstWordIsNeverTouched() {
        Fit f = prose("Called Sarah about Moscow and NASA.", "I think we ", "");
        assertEquals("I think we called Sarah about Moscow and NASA. ",
                field("I think we ", "", f));
    }

    @Test public void aProperNounAtASentenceStartKeepsItsCapital() {
        assertEquals("I went to the shop. Sarah called. ",
                field("I went to the shop. ", "",
                        prose("Sarah called.", "I went to the shop. ", "")));
        assertEquals("Sarah called. ", prose("Sarah called.", "", "").inserted());
    }

    @Test public void noLetterOtherThanTheFirstIsEverChanged() {
        String spoken = "Sarah met McDonald at DHL, then iPhones arrived.";
        Fit f = prose(spoken, "I think ", "");
        assertEquals(spoken.substring(1), f.text.substring(1));
    }

    @Test public void theAppNeverUppercasesAnything() {
        // A sentence start with lower-case model output stays lower case.
        assertEquals("and then we go home. ", prose("and then we go home.", "", "").inserted());
        assertEquals("First line.\nand then. ",
                field("First line.\n", "", prose("and then.", "First line.\n", "")));
    }

    // ----------------------------------------------------------- the caps-mode vote

    @Test public void capsModeCanOnlyKeepACapitalNeverCreateOne() {
        // A continuation by the text, but the target app reports a sentence start.
        Fit voted = TextFitter.fit("And then.", "Hello,", "",
                FieldKind.PROSE, TextFitter.CAP_MODE_SENTENCES);
        assertEquals(" And then. ", voted.inserted());
        // Opposite: the same spot with no vote is a continuation.
        assertNotEquals(voted.inserted(), prose("And then.", "Hello,", "").inserted());
        assertEquals(" and then. ", prose("And then.", "Hello,", "").inserted());
        // The vote never uppercases text the model gave in lower case.
        assertEquals("and then. ", TextFitter.fit("and then.", "", "",
                FieldKind.PROSE, TextFitter.CAP_MODE_SENTENCES).inserted());
    }

    @Test public void theOtherCapsModesVoteTheSameWay() {
        assertEquals(" And then. ", TextFitter.fit("And then.", "Hello,", "",
                FieldKind.PROSE, TextFitter.CAP_MODE_WORDS).inserted());
        assertEquals(" And then. ", TextFitter.fit("And then.", "Hello,", "",
                FieldKind.PROSE, TextFitter.CAP_MODE_CHARACTERS).inserted());
    }
}
