package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PieceJoinerTest {
    @Test public void shortPauseContinuesTheSentence() {
        PieceJoiner joiner = new PieceJoiner();
        assertEquals("I was thinking", joiner.join("I was thinking.", 0f, 3f));
        assertEquals(" that we should go", joiner.join("That we should go.", 1f, 3f));
        assertEquals(". ", joiner.finish());
    }

    @Test public void longPauseKeepsTheSentenceBreak() {
        PieceJoiner joiner = new PieceJoiner();
        assertEquals("First sentence", joiner.join("First sentence.", 0f, 3f));
        assertEquals(". Second sentence", joiner.join("Second sentence.", 4f, 3f));
        assertEquals(". ", joiner.finish());
    }

    @Test public void onePieceKeepsItsOwnEnding() {
        PieceJoiner joiner = new PieceJoiner();
        assertEquals("One word", joiner.join("One word.", 0f, 3f));
        assertEquals(". ", joiner.finish());
    }

    @Test public void acronymsAndThePronounIStayCapitalized() {
        PieceJoiner joiner = new PieceJoiner();
        joiner.join("We discussed.", 0f, 3f);
        assertEquals(" NASA today", joiner.join("NASA today.", 1f, 3f));
        assertEquals(" I agree", joiner.join("I agree.", 1f, 3f));
    }
}
