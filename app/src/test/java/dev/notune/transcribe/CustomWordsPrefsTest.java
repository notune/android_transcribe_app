package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CustomWordsPrefsTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        new File(ctx.getFilesDir(), "custom_words").delete();
    }

    @Test
    public void normalizeTrimsAndCollapses() {
        assertEquals("hello world", CustomWordsPrefs.normalize("  hello   world  "));
        assertEquals("a b c", CustomWordsPrefs.normalize("a\tb\nc"));
        assertNull(CustomWordsPrefs.normalize(null));
        assertNull(CustomWordsPrefs.normalize(""));
        assertNull(CustomWordsPrefs.normalize("   "));
    }

    @Test
    public void addAndGet() {
        assertEquals(CustomWordsPrefs.AddResult.ADDED, CustomWordsPrefs.add(ctx, "Kubernetes"));
        List<String> all = CustomWordsPrefs.getAll(ctx);
        assertEquals(1, all.size());
        assertEquals("Kubernetes", all.get(0));
    }

    @Test
    public void addEmptyRejected() {
        assertEquals(CustomWordsPrefs.AddResult.EMPTY, CustomWordsPrefs.add(ctx, ""));
        assertEquals(CustomWordsPrefs.AddResult.EMPTY, CustomWordsPrefs.add(ctx, "   "));
        assertEquals(CustomWordsPrefs.AddResult.EMPTY, CustomWordsPrefs.add(ctx, null));
        assertTrue(CustomWordsPrefs.getAll(ctx).isEmpty());
    }

    @Test
    public void addTooLongRejected() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < CustomWordsPrefs.MAX_ENTRY_LENGTH + 1; i++) sb.append('x');
        assertEquals(CustomWordsPrefs.AddResult.TOO_LONG, CustomWordsPrefs.add(ctx, sb.toString()));
        assertTrue(CustomWordsPrefs.getAll(ctx).isEmpty());
    }

    @Test
    public void addExactlyMaxLengthAllowed() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < CustomWordsPrefs.MAX_ENTRY_LENGTH; i++) sb.append('x');
        assertEquals(CustomWordsPrefs.AddResult.ADDED, CustomWordsPrefs.add(ctx, sb.toString()));
        assertEquals(1, CustomWordsPrefs.getAll(ctx).size());
    }

    @Test
    public void duplicateIsCaseInsensitiveAndKeepsFirstSpelling() {
        CustomWordsPrefs.add(ctx, "Kubernetes");
        assertEquals(CustomWordsPrefs.AddResult.DUPLICATE, CustomWordsPrefs.add(ctx, "kubernetes"));
        assertEquals(CustomWordsPrefs.AddResult.DUPLICATE, CustomWordsPrefs.add(ctx, "KUBERNETES "));
        List<String> all = CustomWordsPrefs.getAll(ctx);
        assertEquals(1, all.size());
        assertEquals("Kubernetes", all.get(0));
    }

    @Test
    public void orderIsPreserved() {
        CustomWordsPrefs.add(ctx, "alpha");
        CustomWordsPrefs.add(ctx, "beta");
        CustomWordsPrefs.add(ctx, "gamma");
        List<String> all = CustomWordsPrefs.getAll(ctx);
        assertEquals(3, all.size());
        assertEquals("alpha", all.get(0));
        assertEquals("beta", all.get(1));
        assertEquals("gamma", all.get(2));
    }

    @Test
    public void limitReached() {
        for (int i = 0; i < CustomWordsPrefs.MAX_WORDS; i++) {
            assertEquals(CustomWordsPrefs.AddResult.ADDED,
                    CustomWordsPrefs.add(ctx, "word" + i));
        }
        assertEquals(CustomWordsPrefs.AddResult.LIMIT_REACHED,
                CustomWordsPrefs.add(ctx, "overflow"));
        assertEquals(CustomWordsPrefs.MAX_WORDS, CustomWordsPrefs.getAll(ctx).size());
    }

    @Test
    public void removeIsCaseInsensitive() {
        CustomWordsPrefs.add(ctx, "Schopenhauer");
        CustomWordsPrefs.add(ctx, "Nietzsche");
        CustomWordsPrefs.remove(ctx, "schopenhauer");
        List<String> all = CustomWordsPrefs.getAll(ctx);
        assertEquals(1, all.size());
        assertEquals("Nietzsche", all.get(0));
    }

    @Test
    public void updateKeepsPosition() {
        CustomWordsPrefs.add(ctx, "one");
        CustomWordsPrefs.add(ctx, "two");
        CustomWordsPrefs.add(ctx, "three");
        assertEquals(CustomWordsPrefs.AddResult.ADDED, CustomWordsPrefs.update(ctx, "two", "TWO"));
        List<String> all = CustomWordsPrefs.getAll(ctx);
        assertEquals(3, all.size());
        assertEquals("TWO", all.get(1));
    }

    @Test
    public void updateToExistingDuplicateRejected() {
        CustomWordsPrefs.add(ctx, "one");
        CustomWordsPrefs.add(ctx, "two");
        assertEquals(CustomWordsPrefs.AddResult.DUPLICATE,
                CustomWordsPrefs.update(ctx, "two", "ONE"));
        assertEquals("two", CustomWordsPrefs.getAll(ctx).get(1));
    }

    @Test
    public void updateMissingEntryIsEmpty() {
        CustomWordsPrefs.add(ctx, "one");
        assertEquals(CustomWordsPrefs.AddResult.EMPTY,
                CustomWordsPrefs.update(ctx, "ghost", "replacement"));
    }

    @Test
    public void importBulkOnePerLineSkipsBlanksAndDupes() {
        CustomWordsPrefs.add(ctx, "alpha");
        String text = "beta\n\n  gamma  \nALPHA\ndelta\n";
        int added = CustomWordsPrefs.importBulk(ctx, text);
        assertEquals(3, added);
        List<String> all = CustomWordsPrefs.getAll(ctx);
        assertEquals(4, all.size());
        assertEquals("alpha", all.get(0));
        assertEquals("beta", all.get(1));
        assertEquals("gamma", all.get(2));
        assertEquals("delta", all.get(3));
    }

    @Test
    public void importBulkRespectsLimit() {
        for (int i = 0; i < CustomWordsPrefs.MAX_WORDS - 2; i++) {
            CustomWordsPrefs.add(ctx, "word" + i);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append("new").append(i).append('\n');
        int added = CustomWordsPrefs.importBulk(ctx, sb.toString());
        assertEquals(2, added);
        assertEquals(CustomWordsPrefs.MAX_WORDS, CustomWordsPrefs.getAll(ctx).size());
    }

    @Test
    public void importBulkNullReturnsZero() {
        assertEquals(0, CustomWordsPrefs.importBulk(ctx, null));
    }

    @Test
    public void persistsAcrossReads() {
        CustomWordsPrefs.add(ctx, "persisted");
        // A fresh read from disk sees the same data.
        assertEquals("persisted", CustomWordsPrefs.getAll(ctx).get(0));
        CustomWordsPrefs.remove(ctx, "persisted");
        assertTrue(CustomWordsPrefs.getAll(ctx).isEmpty());
    }
}
