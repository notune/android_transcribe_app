package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class KeyboardReturnPolicyTest {
    @Test public void automaticReturnRequiresAcceptedInsertion() {
        assertTrue(KeyboardReturnPolicy.shouldSwitch(false, true, true));
        assertFalse(KeyboardReturnPolicy.shouldSwitch(false, false, true));
        assertFalse(KeyboardReturnPolicy.shouldSwitch(false, true, false));
    }

    @Test public void manualSwitchRemainsIndependent() {
        assertTrue(KeyboardReturnPolicy.shouldSwitch(true, false, false));
    }

    @Test public void readableEditorMustShowTheExactCommit() {
        KeyboardReturnPolicy.EditorSnapshot before =
                new KeyboardReturnPolicy.EditorSnapshot("hello world", 0, 6, 11);
        KeyboardReturnPolicy.EditorSnapshot committed =
                new KeyboardReturnPolicy.EditorSnapshot("hello there ", 0, 12, 12);
        KeyboardReturnPolicy.EditorSnapshot rejected =
                new KeyboardReturnPolicy.EditorSnapshot("hello world", 0, 6, 11);

        assertTrue(committed.isExactCommitOf(before, "there "));
        assertFalse(rejected.isExactCommitOf(before, "there "));
        assertTrue(rejected.isKnownMismatchFrom(before, "there "));
    }

    @Test public void transformedOrThrowingCommitNeverAutoReplaysOnRefocus() {
        KeyboardReturnPolicy.EditorSnapshot before =
                new KeyboardReturnPolicy.EditorSnapshot("hello", 0, 5, 5);
        KeyboardReturnPolicy.EditorSnapshot transformed =
                new KeyboardReturnPolicy.EditorSnapshot("hello THERE", 0, 11, 11);

        KeyboardReturnPolicy.InsertionResult changed =
                KeyboardReturnPolicy.classifyInsertion(
                        true, false, before, transformed, " there");
        KeyboardReturnPolicy.InsertionResult threw =
                KeyboardReturnPolicy.classifyInsertion(
                        false, true, before, null, " there");

        assertFalse(KeyboardReturnPolicy.shouldAutoReplay(changed));
        assertFalse(KeyboardReturnPolicy.shouldAutoReplay(threw));
    }

    @Test public void definiteRejectionCanRetryButUnreadableAcceptanceSucceeds() {
        assertTrue(KeyboardReturnPolicy.shouldAutoReplay(
                KeyboardReturnPolicy.classifyInsertion(
                        false, false, null, null, "text")));
        assertTrue(KeyboardReturnPolicy.classifyInsertion(
                true, false, null, null, "text")
                == KeyboardReturnPolicy.InsertionResult.ACCEPTED);
    }

    @Test public void nonzeroWindowUsesLocalSnapshotAndGlobalSelectionCoordinates() {
        KeyboardReturnPolicy.EditorSnapshot before =
                new KeyboardReturnPolicy.EditorSnapshot("hello world", 100, 6, 11);
        KeyboardReturnPolicy.EditorSnapshot after =
                new KeyboardReturnPolicy.EditorSnapshot("hello there ", 100, 12, 12);

        assertTrue(after.isComparableTo(before));
        assertTrue(after.isExactCommitOf(before, "there "));
        assertEquals(112, after.globalSelectionStart());
        assertEquals(106, after.globalSelectionStart() - "there ".length());
    }

    @Test public void reversedSelectionUsesItsLocalBounds() {
        KeyboardReturnPolicy.EditorSnapshot before =
                new KeyboardReturnPolicy.EditorSnapshot("hello world", 100, 11, 6);
        KeyboardReturnPolicy.EditorSnapshot after =
                new KeyboardReturnPolicy.EditorSnapshot("hello there ", 100, 12, 12);

        assertTrue(after.isExactCommitOf(before, "there "));
    }

    @Test public void shiftedWindowLeavesAcceptedCommitUnconfirmed() {
        KeyboardReturnPolicy.EditorSnapshot before =
                new KeyboardReturnPolicy.EditorSnapshot("hello world", 100, 6, 11);
        KeyboardReturnPolicy.EditorSnapshot shifted =
                new KeyboardReturnPolicy.EditorSnapshot("ello there ", 101, 11, 11);

        assertFalse(shifted.isComparableTo(before));
        assertFalse(shifted.isKnownMismatchFrom(before, "there "));
        assertTrue(KeyboardReturnPolicy.classifyInsertion(
                true, false, before, shifted, "there ")
                == KeyboardReturnPolicy.InsertionResult.ACCEPTED);
    }
}
