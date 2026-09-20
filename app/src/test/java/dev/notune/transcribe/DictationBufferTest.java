package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DictationBufferTest {
    @Test public void emptyFirstPieceAdvancesToLaterWords() {
        DictationBuffer buffer = new DictationBuffer();
        buffer.reset(7);

        assertTrue(buffer.accept(7, 0, "", 0f, 3f));
        assertTrue(buffer.accept(7, 1, "Hello.", 1f, 3f));
        assertEquals("Hello.", buffer.finish(7));
    }

    @Test public void emptyMiddlePieceDoesNotDropOrDuplicateWords() {
        DictationBuffer buffer = new DictationBuffer();
        buffer.reset(7);

        assertTrue(buffer.accept(7, 0, "First part.", 0f, 3f));
        assertTrue(buffer.accept(7, 1, " ", 1f, 3f));
        assertTrue(buffer.accept(7, 1, " ", 1f, 3f));
        assertTrue(buffer.accept(7, 2, "Second part.", 1f, 3f));
        assertEquals("First part second part.", buffer.finish(7));
    }

    @Test public void emptyFinalPieceCompletesPriorWords() {
        DictationBuffer buffer = new DictationBuffer();
        buffer.reset(7);

        assertTrue(buffer.accept(7, 0, "Hello.", 0f, 3f));
        assertTrue(buffer.accept(7, 1, "", 1f, 3f));
        assertEquals("Hello.", buffer.finish(7));
    }

    @Test public void sameSessionIsOrderedAndDuplicateDeliveryIsIdempotent() {
        DictationBuffer buffer = new DictationBuffer();
        buffer.reset(7);

        assertTrue(buffer.accept(7, 0, "First part.", 0f, 3f));
        assertTrue(buffer.accept(7, 0, "First part.", 0f, 3f));
        assertFalse(buffer.accept(7, 2, "Third part.", 1f, 3f));
        assertTrue(buffer.accept(7, 1, "Second part.", 1f, 3f));
        assertEquals("First part second part.", buffer.finish(7));
    }

    @Test public void staleSessionCannotChangeTheCurrentResult() {
        DictationBuffer buffer = new DictationBuffer();
        buffer.reset(7);
        assertTrue(buffer.accept(7, 0, "Old session.", 0f, 3f));

        buffer.reset(8);
        assertFalse(buffer.accept(7, 1, "Stale words.", 1f, 3f));
        assertNull(buffer.finish(7));
        assertTrue(buffer.accept(8, 0, "Current words.", 0f, 3f));
        assertEquals("Current words.", buffer.finish(8));
    }
}
