package dev.notune.transcribe;

import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Edits the custom-word list that biases Whisper recognition (see
 * {@link CustomWordsPrefs}). Supports single add/edit/remove and a one-per-line
 * bulk import. Every change reloads the shared engine through the same native
 * path the Models screen uses, so the new initial prompt applies to all
 * transcription entry points (keyboard, bubble, subtitles, shared files).
 */
public class CustomWordsActivity extends AppCompatActivity {

    static {
        try {
            System.loadLibrary("c++_shared");
        } catch (UnsatisfiedLinkError ignored) { }
        System.loadLibrary("android_transcribe_app");
    }

    private RecyclerView recyclerView;
    private WordsAdapter adapter;
    private View emptyView;
    private TextView statusText;
    private TextView countText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_custom_words);

        setSupportActionBar(findViewById(R.id.toolbar));
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        statusText = findViewById(R.id.txt_custom_words_status);
        countText = findViewById(R.id.txt_custom_words_count);
        recyclerView = findViewById(R.id.custom_words_list);
        emptyView = findViewById(R.id.custom_words_empty);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new WordsAdapter();
        recyclerView.setAdapter(adapter);

        findViewById(R.id.btn_custom_words_add).setOnClickListener(v -> showAddDialog());
        findViewById(R.id.btn_custom_words_import).setOnClickListener(v -> showImportDialog());
        findViewById(R.id.btn_custom_words_help).setOnClickListener(v -> showHelpDialog());

        refreshStatus();
        loadWords();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    // --- Active model status ------------------------------------------------

    /** Name of the selected model, for the status line. */
    private String activeModelName() {
        String active = readConfig("active_model");
        return active.isEmpty() ? getString(R.string.custom_words_builtin) : active;
    }

    /**
     * Best-effort check for whether the active model is a Whisper model, which
     * is the only family that uses custom words. The built-in model is Parakeet
     * (not Whisper); imported GGUFs are judged by their file name.
     */
    private boolean activeModelIsWhisper() {
        String active = readConfig("active_model");
        return !active.isEmpty() && active.toLowerCase(Locale.ROOT).contains("whisper");
    }

    private void refreshStatus() {
        String status = getString(R.string.custom_words_active_model, activeModelName());
        if (!activeModelIsWhisper()) {
            status += "\n" + getString(R.string.custom_words_not_whisper);
        }
        statusText.setText(status);
    }

    // --- List ---------------------------------------------------------------

    private void loadWords() {
        List<String> words = CustomWordsPrefs.getAll(this);
        adapter.setWords(words);
        boolean empty = words.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
        countText.setText(getResources().getQuantityString(
                R.plurals.custom_words_count, words.size(), words.size()));
    }

    /** Persists a change made through the UI and reloads the shared engine. */
    private void applyAndReload() {
        loadWords();
        reloadModelNative(this);
    }

    // --- Add / edit / remove ------------------------------------------------

    private void showAddDialog() {
        EditText input = newSingleLineInput();
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.custom_words_add)
                .setView(wrapInput(input))
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    CustomWordsPrefs.AddResult r = CustomWordsPrefs.add(this, input.getText().toString());
                    reportAdd(r, input.getText().toString());
                    if (r == CustomWordsPrefs.AddResult.ADDED) applyAndReload();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showEditDialog(String current) {
        EditText input = newSingleLineInput();
        input.setText(current);
        input.setSelection(current.length());
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.custom_words_edit)
                .setView(wrapInput(input))
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    CustomWordsPrefs.AddResult r =
                            CustomWordsPrefs.update(this, current, input.getText().toString());
                    reportAdd(r, input.getText().toString());
                    if (r == CustomWordsPrefs.AddResult.ADDED) applyAndReload();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showImportDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(5);
        input.setGravity(android.view.Gravity.TOP);
        input.setHint(R.string.custom_words_import_hint);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.custom_words_import)
                .setView(wrapInput(input))
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    int added = CustomWordsPrefs.importBulk(this, input.getText().toString());
                    if (added > 0) {
                        snackbar(getString(R.string.custom_words_import_done, added));
                        applyAndReload();
                    } else {
                        snackbar(getString(R.string.custom_words_import_none));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Maps an add/edit outcome to user feedback. */
    private void reportAdd(CustomWordsPrefs.AddResult r, String raw) {
        switch (r) {
            case ADDED:
                snackbar(getString(R.string.custom_words_saved));
                break;
            case EMPTY:
                snackbar(getString(R.string.custom_words_empty_input));
                break;
            case TOO_LONG:
                snackbar(getString(R.string.custom_words_too_long,
                        CustomWordsPrefs.MAX_ENTRY_LENGTH));
                break;
            case DUPLICATE:
                snackbar(getString(R.string.custom_words_duplicate));
                break;
            case LIMIT_REACHED:
                snackbar(getString(R.string.custom_words_limit, CustomWordsPrefs.MAX_WORDS));
                break;
        }
    }

    // --- Privacy / help -----------------------------------------------------

    private void showHelpDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.custom_words_help_title)
                .setMessage(R.string.custom_words_help_body)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    // --- Helpers ------------------------------------------------------------

    private EditText newSingleLineInput() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        input.setHint(R.string.custom_words_add_hint);
        return input;
    }

    /** Wraps an EditText in padding so dialog content isn't flush to the edge. */
    private View wrapInput(View input) {
        FrameLayout container = new FrameLayout(this);
        int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20,
                getResources().getDisplayMetrics());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(pad, pad / 2, pad, 0);
        container.addView(input, lp);
        container.setPadding(0, 0, 0, pad / 2);
        return container;
    }

    private String readConfig(String name) {
        File f = new File(getFilesDir(), name);
        if (!f.exists()) return "";
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private void snackbar(String message) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG).show();
    }

    // Called from Rust with reload progress ("Loading model...", "Ready", ...).
    public void onStatusUpdate(String status) {
        runOnUiThread(() -> statusText.setText(status));
    }

    private native void reloadModelNative(CustomWordsActivity activity);

    // --- Adapter ------------------------------------------------------------

    private class WordsAdapter extends RecyclerView.Adapter<WordsAdapter.VH> {
        private final List<String> words = new ArrayList<>();

        void setWords(List<String> list) {
            words.clear();
            words.addAll(list);
            notifyDataSetChanged();
        }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_custom_word, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(VH h, int position) {
            String word = words.get(position);
            h.text.setText(word);
            h.editBtn.setOnClickListener(v -> showEditDialog(word));
            h.deleteBtn.setOnClickListener(v -> {
                CustomWordsPrefs.remove(CustomWordsActivity.this, word);
                applyAndReload();
            });
        }

        @Override
        public int getItemCount() {
            return words.size();
        }

        class VH extends RecyclerView.ViewHolder {
            TextView text;
            ImageButton editBtn, deleteBtn;

            VH(View v) {
                super(v);
                text = v.findViewById(R.id.custom_word_text);
                editBtn = v.findViewById(R.id.custom_word_edit);
                deleteBtn = v.findViewById(R.id.custom_word_delete);
            }
        }
    }
}
