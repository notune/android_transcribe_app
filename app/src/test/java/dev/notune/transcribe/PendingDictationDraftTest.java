package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class PendingDictationDraftTest {
    @Test public void roundTripKeepsUnlimitedUnicodeText() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 10000; i++) text.append("Привет\nhello ");
        PendingDictationDraft restored = PendingDictationDraft.decode(
                new PendingDictationDraft(PendingDictationDraft.PENDING, text.toString()).encode());
        assertEquals(PendingDictationDraft.PENDING, restored.state);
        assertEquals(text.toString(), restored.text);
        assertFalse(restored.mayAlreadyBeDelivered());
    }

    @Test public void attemptedDeliverySurvivesRestart() {
        PendingDictationDraft restored = PendingDictationDraft.decode(
                new PendingDictationDraft(PendingDictationDraft.ATTEMPTED,
                        "May already be in the field.").encode());
        assertEquals(PendingDictationDraft.ATTEMPTED, restored.state);
        assertTrue(restored.mayAlreadyBeDelivered());
    }

    @Test public void corruptOrUnknownRecordsAreRejected() {
        assertNull(PendingDictationDraft.decode("broken".getBytes(StandardCharsets.UTF_8)));
        assertNull(PendingDictationDraft.decode(
                "NOTUNE1\nunknown\ndGV4dA==".getBytes(StandardCharsets.UTF_8)));
    }

    @Test public void readableEditorMustShowTheExactCommittedText() {
        RustInputMethodService.EditorSnapshot before =
                new RustInputMethodService.EditorSnapshot("hello world", 0, 6, 11);
        RustInputMethodService.EditorSnapshot committed =
                new RustInputMethodService.EditorSnapshot("hello there ", 0, 12, 12);
        RustInputMethodService.EditorSnapshot rejected =
                new RustInputMethodService.EditorSnapshot("hello world", 0, 6, 11);
        assertTrue(committed.isExactCommitOf(before, "there "));
        assertFalse(rejected.isExactCommitOf(before, "there "));
        assertTrue(rejected.isKnownMismatchFrom(before, "there "));
    }

    @Test public void nonzeroWindowUsesLocalSnapshotAndGlobalSelectionCoordinates() {
        RustInputMethodService.EditorSnapshot before =
                new RustInputMethodService.EditorSnapshot("hello world", 100, 6, 11);
        RustInputMethodService.EditorSnapshot after =
                new RustInputMethodService.EditorSnapshot("hello there ", 100, 12, 12);

        assertTrue(after.isComparableTo(before));
        assertTrue(after.isExactCommitOf(before, "there "));
        assertEquals(112, after.globalSelectionStart());
        assertEquals(106, after.globalSelectionStart() - "there ".length());
    }

    @Test public void reversedSelectionUsesItsLocalBounds() {
        RustInputMethodService.EditorSnapshot before =
                new RustInputMethodService.EditorSnapshot("hello world", 100, 11, 6);
        RustInputMethodService.EditorSnapshot after =
                new RustInputMethodService.EditorSnapshot("hello there ", 100, 12, 12);
        assertTrue(after.isExactCommitOf(before, "there "));
    }

    @Test public void shiftedWindowIsNotComparable() {
        RustInputMethodService.EditorSnapshot before =
                new RustInputMethodService.EditorSnapshot("hello world", 100, 6, 11);
        RustInputMethodService.EditorSnapshot shifted =
                new RustInputMethodService.EditorSnapshot("ello there ", 101, 11, 11);
        assertFalse(shifted.isComparableTo(before));
        assertFalse(shifted.isKnownMismatchFrom(before, "there "));
    }

    @Test public void confirmedNewResultLeavesOlderRejectedTextRecoverable() {
        PendingDictationDraft rejectedA =
                new PendingDictationDraft(PendingDictationDraft.PENDING, "Rejected A. ");
        PendingDictationDraft stagedB = rejectedA.stageCurrent("Confirmed B. ");

        assertEquals("Rejected A. ", stagedB.savedText);
        assertEquals("Confirmed B. ", stagedB.currentText);
        PendingDictationDraft afterConfirmedB = stagedB.withoutCurrent();
        assertEquals("Rejected A. ", afterConfirmedB.recoveryText());
        assertFalse(afterConfirmedB.hasCurrent());
    }

    @Test public void secondRejectedResultDoesNotEraseTheFirst() {
        PendingDictationDraft rejectedA =
                new PendingDictationDraft(PendingDictationDraft.PENDING, "Rejected A. ");
        PendingDictationDraft rejectedAAndB = rejectedA.stageCurrent("Rejected B. ");

        assertEquals("Rejected A. Rejected B. ", rejectedAAndB.recoveryText());
        assertEquals("Rejected B. ", rejectedAAndB.currentText);
    }

    @Test public void recoverySeparatesTrimmedResultsWithoutChangingSingleResultBytes() {
        PendingDictationDraft attemptedA =
                new PendingDictationDraft(PendingDictationDraft.ATTEMPTED, "alpha");
        assertEquals("alpha", attemptedA.recoveryText());
        assertEquals("alpha beta", attemptedA.stageCurrent("beta").recoveryText());
    }

    @Test public void normalConfirmedResultClearsItsTemporaryState() {
        PendingDictationDraft normal =
                new PendingDictationDraft(PendingDictationDraft.PENDING, "Normal. ");
        assertTrue(normal.withoutCurrent().isEmpty());
    }

    @Test public void olderRecoveryAndCurrentStateSurviveRestart() {
        PendingDictationDraft uncertainA =
                new PendingDictationDraft(PendingDictationDraft.ATTEMPTED, "Uncertain A. ");
        PendingDictationDraft stagedB = uncertainA.stageCurrent("Current B. ");
        PendingDictationDraft restored = PendingDictationDraft.decode(stagedB.encode());

        assertEquals("Uncertain A. ", restored.savedText);
        assertEquals("Current B. ", restored.currentText);
        assertTrue(restored.mayAlreadyBeDelivered());
    }

    @Test public void versionOneRecordMigratesWithoutLosingItsText() {
        PendingDictationDraft old = PendingDictationDraft.decode(
                "NOTUNE1\npending\nT2xkIGRyYWZ0LiA=".getBytes(StandardCharsets.UTF_8));
        PendingDictationDraft staged = old.stageCurrent("New result. ");

        assertEquals("Old draft. ", staged.savedText);
        assertEquals("New result. ", staged.currentText);
        assertEquals("Old draft. New result. ",
                PendingDictationDraft.decode(staged.encode()).recoveryText());
    }

    @Test public void onlyExactReadableCommitClearsTheSavedCopy() {
        RustInputMethodService.EditorSnapshot before =
                new RustInputMethodService.EditorSnapshot("hello", 0, 5, 5);
        RustInputMethodService.EditorSnapshot exact =
                new RustInputMethodService.EditorSnapshot("hello world", 0, 11, 11);
        RustInputMethodService.EditorSnapshot changed =
                new RustInputMethodService.EditorSnapshot("hello WORLD", 0, 11, 11);

        assertTrue(RustInputMethodService.EditorSnapshot.isConfirmedCommit(
                before, exact, " world"));
        assertFalse(RustInputMethodService.EditorSnapshot.isConfirmedCommit(
                before, changed, " world"));
        assertFalse(RustInputMethodService.EditorSnapshot.isConfirmedCommit(
                before, null, " world"));
        assertFalse(RustInputMethodService.EditorSnapshot.isConfirmedCommit(
                null, exact, " world"));
    }
}
