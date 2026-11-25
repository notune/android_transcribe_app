package dev.notune.transcribe;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Field ids are only a strong identity when the editor actually assigned one.
 * Both 0 and {@code View.NO_ID} (-1) mean "no id".
 */
public class EditorTargetTest {

    @Test
    public void positiveViewIdIsAStrongIdentity() {
        assertTrue(EditorTarget.isStrongFieldId(123));
    }

    @Test
    public void zeroAndNoIdAreNotStrongIdentities() {
        assertFalse(EditorTarget.isStrongFieldId(0));
        assertFalse(EditorTarget.isStrongFieldId(-1));
    }
}
