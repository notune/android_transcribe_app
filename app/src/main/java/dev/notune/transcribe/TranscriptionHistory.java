package dev.notune.transcribe;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public final class TranscriptionHistory extends SQLiteOpenHelper {

    private static final String DB_NAME = "transcription_history.db";
    private static final int DB_VERSION = 1;

    public static final String TABLE = "history";
    public static final String COL_ID = "_id";
    public static final String COL_TEXT = "text";
    public static final String COL_SOURCE = "source";
    public static final String COL_TIMESTAMP = "timestamp";

    public static final String SOURCE_BUBBLE = "bubble";
    public static final String SOURCE_POPUP = "popup";
    public static final String SOURCE_IME = "ime";
    public static final String SOURCE_FILE = "file";
    public static final String SOURCE_SERVICE = "service";

    private static volatile TranscriptionHistory sInstance;
    private final Context appCtx;

    public static TranscriptionHistory get(Context ctx) {
        if (sInstance == null) {
            synchronized (TranscriptionHistory.class) {
                if (sInstance == null) {
                    sInstance = new TranscriptionHistory(ctx.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private TranscriptionHistory(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
        appCtx = ctx;
    }

    static void resetForTesting() {
        sInstance = null;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_TEXT + " TEXT NOT NULL, "
                + COL_SOURCE + " TEXT NOT NULL, "
                + COL_TIMESTAMP + " INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_ts ON " + TABLE + " (" + COL_TIMESTAMP + " DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    public void insert(String text, String source) {
        if (text == null || text.trim().isEmpty()) return;
        int retention = HistoryPrefs.getRetention(appCtx);
        if (retention == 0) return;
        ContentValues cv = new ContentValues(3);
        cv.put(COL_TEXT, text.trim());
        cv.put(COL_SOURCE, source);
        cv.put(COL_TIMESTAMP, System.currentTimeMillis());
        getWritableDatabase().insert(TABLE, null, cv);
        if (retention > 0) prune(retention);
    }

    public List<Entry> query(int limit) {
        List<Entry> list = new ArrayList<>();
        String lim = limit > 0 ? String.valueOf(limit) : null;
        try (Cursor c = getReadableDatabase().query(TABLE,
                new String[]{COL_ID, COL_TEXT, COL_SOURCE, COL_TIMESTAMP},
                null, null, null, null,
                COL_TIMESTAMP + " DESC, " + COL_ID + " DESC", lim)) {
            while (c.moveToNext()) {
                list.add(new Entry(
                        c.getLong(0), c.getString(1), c.getString(2), c.getLong(3)));
            }
        }
        return list;
    }

    public void delete(long id) {
        getWritableDatabase().delete(TABLE, COL_ID + "=?", new String[]{String.valueOf(id)});
    }

    public void clearAll() {
        getWritableDatabase().delete(TABLE, null, null);
    }

    public void prune() {
        int retention = HistoryPrefs.getRetention(appCtx);
        if (retention <= 0) return;
        prune(retention);
    }

    private void prune(int retention) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("DELETE FROM " + TABLE + " WHERE " + COL_ID + " NOT IN ("
                + "SELECT " + COL_ID + " FROM " + TABLE
                + " ORDER BY " + COL_TIMESTAMP + " DESC, " + COL_ID + " DESC LIMIT "
                + retention + ")");
    }

    public static final class Entry {
        public final long id;
        public final String text;
        public final String source;
        public final long timestamp;

        Entry(long id, String text, String source, long timestamp) {
            this.id = id;
            this.text = text;
            this.source = source;
            this.timestamp = timestamp;
        }
    }
}
