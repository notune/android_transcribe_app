package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TextSpliceTest {

    static void check(String existing, int s, int e, String dictation, String expected, int cursor) {
        TextSplice r = TextSplice.at(existing, s, e, dictation);
        assertEquals(expected, r.text);
        assertEquals(cursor, r.cursor);
    }

    @Test
    public void emptyField() {
        check("", 0, 0, "hello world", "hello world", 11);
        check(null, -1, -1, "hello", "hello", 5);
    }

    @Test
    public void appendsWithASpace() {
        check("I said", 6, 6, "hello", "I said hello", 12);
        check("I said ", 7, 7, "hello", "I said hello", 12);
    }

    @Test
    public void unknownCursorAppendsAtEnd() {
        check("abc", -1, -1, "def", "abc def", 7);
    }

    @Test
    public void insertsInTheMiddleWithSpaces() {
        check("onetwo", 3, 3, "and", "one and two", 8);
    }

    @Test
    public void replacesTheSelection() {
        check("say REPLACE please", 4, 11, "hello", "say hello please", 9);
        check("say REPLACE please", 11, 4, "hello", "say hello please", 9); // reversed
    }

    @Test
    public void noSpaceBeforePunctuation() {
        check("Hello", 5, 5, ", world", "Hello, world", 12);
        check("Hello", 5, 5, "!", "Hello!", 6);
        check("Hello.", 5, 5, "there", "Hello there.", 11);
    }

    @Test
    public void trimsTheDictation() {
        check("", 0, 0, "  hi  ", "hi", 2);
    }

    @Test
    public void clampsOutOfRangeSelection() {
        check("ab", 10, 20, "c", "ab c", 4);
    }
}
