package dev.notune.transcribe;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONObject;

public class VocabActivity extends AppCompatActivity {
    private static final String TAG = "VocabActivity";

    private LinearLayout list;
    private TextView empty;
    private TextInputEditText input;
    private JSONObject store;

    static {
        try {
            System.loadLibrary("c++_shared");
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "Failed to load c++_shared", e);
        }
        System.loadLibrary("android_transcribe_app");
    }

    private native String loadVocabNative(VocabActivity activity);
    private native boolean saveVocabNative(VocabActivity activity, String json);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vocab);
        MaterialToolbar bar = findViewById(R.id.vocab_toolbar);
        bar.setNavigationOnClickListener(v -> finish());

        list = findViewById(R.id.vocab_list);
        empty = findViewById(R.id.vocab_empty);
        input = findViewById(R.id.vocab_input);
        findViewById(R.id.btn_vocab_add).setOnClickListener(v -> addFromInput());
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addFromInput();
                return true;
            }
            return false;
        });

        loadStore();
        render();
    }

    private void loadStore() {
        try {
            String json = loadVocabNative(this);
            store = (json == null || json.isEmpty()) ? new JSONObject() : new JSONObject(json);
        } catch (Exception e) {
            Log.e(TAG, "load vocab", e);
            store = new JSONObject();
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "load vocab native", e);
            store = new JSONObject();
        }
        // matching ignores this flag; do not rewrite on-device json on open.
    }

    private JSONArray entries() {
        JSONArray a = store.optJSONArray("entries");
        if (a == null) {
            a = new JSONArray();
            try { store.put("entries", a); } catch (Exception ignored) {}
        }
        return a;
    }

    private void persist() {
        try {
            if (!saveVocabNative(this, store.toString())) {
                Snackbar.make(list, "Save failed", Snackbar.LENGTH_SHORT).show();
            }
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "save vocab native", e);
        }
    }

    private static String matchKey(String word) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < word.length(); ) {
            int cp = word.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) {
                b.appendCodePoint(Character.toLowerCase(cp));
            }
            i += Character.charCount(cp);
        }
        return b.toString();
    }

    private void addFromInput() {
        String word = input.getText() == null ? "" : input.getText().toString().trim();
        if (word.isEmpty()) {
            Snackbar.make(list, R.string.vocab_need_word, Snackbar.LENGTH_SHORT).show();
            return;
        }
        String key = matchKey(word);
        if (key.isEmpty()) {
            Snackbar.make(list, R.string.vocab_need_word, Snackbar.LENGTH_SHORT).show();
            return;
        }
        JSONArray arr = entries();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e != null && key.equals(matchKey(e.optString("canonical")))) {
                try { e.put("canonical", word); } catch (Exception ignored) {}
                persist();
                render();
                input.setText("");
                return;
            }
        }
        try {
            JSONObject e = new JSONObject();
            e.put("canonical", word);
            e.put("aliases", new JSONArray());
            e.put("enabled", true);
            e.put("fuzzy", true);
            arr.put(e);
            persist();
            render();
            input.setText("");
        } catch (Exception ex) {
            Log.e(TAG, "add word", ex);
        }
    }

    private void deleteAt(int index) {
        JSONArray arr = entries();
        JSONArray next = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            if (i != index) next.put(arr.opt(i));
        }
        try { store.put("entries", next); } catch (Exception ignored) {}
        persist();
        render();
    }

    private void render() {
        list.removeAllViews();
        JSONArray arr = entries();
        empty.setVisibility(arr.length() == 0 ? View.VISIBLE : View.GONE);
        LayoutInflater inf = LayoutInflater.from(this);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null) continue;
            View row = inf.inflate(R.layout.item_vocab_entry, list, false);
            TextView title = row.findViewById(R.id.vocab_item_title);
            title.setText(e.optString("canonical"));
            ImageButton del = row.findViewById(R.id.vocab_item_delete);
            final int idx = i;
            del.setOnClickListener(v -> deleteAt(idx));
            list.addView(row);
        }
    }
}
