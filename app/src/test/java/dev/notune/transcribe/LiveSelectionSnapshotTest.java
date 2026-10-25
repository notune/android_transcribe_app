package dev.notune.transcribe;

import org.junit.Test;
import static org.junit.Assert.*;

public class LiveSelectionSnapshotTest {
    @Test public void missingSelectedTextCannotOverwriteASelection() {
        assertNull(LiveSelectionSnapshot.capture(3, 7, null));
    }
    @Test public void partialSelectedTextCannotOverwriteASelection() {
        assertNull(LiveSelectionSnapshot.capture(3, 7, "KE"));
    }
    @Test public void fullSelectedTextCanBeRestored() {
        assertEquals("KEEP", LiveSelectionSnapshot.capture(3, 7, "KEEP"));
    }
    @Test public void reversedSelectionCanBeRestored() {
        assertEquals("KEEP", LiveSelectionSnapshot.capture(7, 3, "KEEP"));
    }
    @Test public void nullIsValidForAnEmptySelection() {
        assertEquals("", LiveSelectionSnapshot.capture(3, 3, null));
    }
    @Test public void emptyTextIsValidForAnEmptySelection() {
        assertEquals("", LiveSelectionSnapshot.capture(3, 3, ""));
    }
    @Test public void inconsistentEmptySelectionIsRejected() {
        assertNull(LiveSelectionSnapshot.capture(3, 3, "not empty"));
    }
    @Test public void invalidOffsetsAreRejected() {
        assertNull(LiveSelectionSnapshot.capture(-1, 3, "KEEP"));
        assertNull(LiveSelectionSnapshot.capture(3, -1, "KEEP"));
    }
    @Test public void unicodeUsesEditorUtf16Offsets() {
        String text = "\uD83D\uDE00é";
        assertEquals(text, LiveSelectionSnapshot.capture(2, 5, text));
        assertNull(LiveSelectionSnapshot.capture(2, 4, text));
    }
}
