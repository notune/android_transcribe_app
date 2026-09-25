package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TranscriptionHistoryTest {

    private Context ctx;
    private TranscriptionHistory db;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        TranscriptionHistory.resetForTesting();
        db = TranscriptionHistory.get(ctx);
        db.clearAll();
        HistoryPrefs.setRetention(ctx, HistoryPrefs.DEFAULT_RETENTION);
    }

    @After
    public void tearDown() {
        db.clearAll();
        HistoryPrefs.setRetention(ctx, HistoryPrefs.DEFAULT_RETENTION);
        TranscriptionHistory.resetForTesting();
    }

    @Test
    public void insertAndQuery() {
        db.insert("hello world", TranscriptionHistory.SOURCE_BUBBLE);
        List<TranscriptionHistory.Entry> entries = db.query(0);
        assertEquals(1, entries.size());
        assertEquals("hello world", entries.get(0).text);
        assertEquals(TranscriptionHistory.SOURCE_BUBBLE, entries.get(0).source);
        assertTrue(entries.get(0).timestamp > 0);
    }

    @Test
    public void insertEmptyIgnored() {
        db.insert("", TranscriptionHistory.SOURCE_BUBBLE);
        db.insert("   ", TranscriptionHistory.SOURCE_BUBBLE);
        db.insert(null, TranscriptionHistory.SOURCE_BUBBLE);
        assertEquals(0, db.query(0).size());
    }

    @Test
    public void newestFirst() throws InterruptedException {
        db.insert("first", TranscriptionHistory.SOURCE_POPUP);
        Thread.sleep(10);
        db.insert("second", TranscriptionHistory.SOURCE_IME);
        List<TranscriptionHistory.Entry> entries = db.query(0);
        assertEquals(2, entries.size());
        assertEquals("second", entries.get(0).text);
        assertEquals("first", entries.get(1).text);
    }

    @Test
    public void deleteEntry() {
        db.insert("to delete", TranscriptionHistory.SOURCE_FILE);
        List<TranscriptionHistory.Entry> entries = db.query(0);
        assertEquals(1, entries.size());
        db.delete(entries.get(0).id);
        assertEquals(0, db.query(0).size());
    }

    @Test
    public void clearAll() {
        db.insert("one", TranscriptionHistory.SOURCE_BUBBLE);
        db.insert("two", TranscriptionHistory.SOURCE_POPUP);
        db.clearAll();
        assertEquals(0, db.query(0).size());
    }

    @Test
    public void pruneRespectsRetention() {
        HistoryPrefs.setRetention(ctx, 3);
        for (int i = 0; i < 10; i++) {
            db.insert("entry " + i, TranscriptionHistory.SOURCE_BUBBLE);
        }
        List<TranscriptionHistory.Entry> entries = db.query(0);
        assertTrue(entries.size() <= 3);
        HistoryPrefs.setRetention(ctx, HistoryPrefs.DEFAULT_RETENTION);
    }

    @Test
    public void pruneDisabledWhenOff() {
        HistoryPrefs.setRetention(ctx, 0);
        for (int i = 0; i < 30; i++) {
            db.insert("entry " + i, TranscriptionHistory.SOURCE_BUBBLE);
        }
        assertEquals(0, db.query(0).size());
    }

    @Test
    public void unlimitedRetentionKeepsAll() {
        HistoryPrefs.setRetention(ctx, -1);
        for (int i = 0; i < 30; i++) {
            db.insert("entry " + i, TranscriptionHistory.SOURCE_BUBBLE);
        }
        assertEquals(30, db.query(0).size());
    }

    @Test
    public void queryWithLimit() {
        for (int i = 0; i < 10; i++) {
            db.insert("entry " + i, TranscriptionHistory.SOURCE_BUBBLE);
        }
        assertEquals(5, db.query(5).size());
    }
}
