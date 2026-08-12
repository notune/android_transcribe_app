package dev.notune.transcribe;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.format.Formatter;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Lets the user pick the speech model: the built-in Parakeet model, or any
 * transcribe.cpp GGUF model imported from a locally downloaded file. Import
 * goes through the system file picker (SAF), so the app itself needs no
 * internet or storage permission — download links are only *shown* here and
 * open in the browser.
 *
 * The selection is stored as marker files in filesDir (same pattern as the
 * other settings), read by the Rust engine loader:
 *   - {@code active_model}: file name of the GGUF under {@code files/models/},
 *     absent/empty = built-in model.
 *   - {@code model_language}: optional language hint for GGUF models.
 */
public class ModelsActivity extends AppCompatActivity {
    private static final String TAG = "ModelsActivity";
    private static final int REQ_PICK_MODEL = 301;
    /** Keep this much free space beyond the model file itself. */
    private static final long FREE_SPACE_MARGIN = 64L * 1024 * 1024;

    static {
        NativeLibraries.load();
    }

    /** One entry in the curated download list. Names are proper nouns and stay
     *  untranslated; descriptions are string resources so they localize. */
    private static final class ModelLink {
        final String name;
        final int descRes;
        final String size;
        final String url;

        ModelLink(String name, int descRes, String size, String url) {
            this.name = name;
            this.descRes = descRes;
            this.size = size;
            this.url = url;
        }
    }

    private static final ModelLink[] MODEL_LINKS = {
            new ModelLink("Parakeet TDT 110M", R.string.model_desc_parakeet110m, "135 MB",
                    "https://huggingface.co/handy-computer/parakeet-tdt_ctc-110m-gguf/resolve/main/parakeet-tdt_ctc-110m-Q8_0.gguf"),
            new ModelLink("SenseVoice Small", R.string.model_desc_sensevoice, "241 MB",
                    "https://huggingface.co/handy-computer/SenseVoiceSmall-gguf/resolve/main/SenseVoiceSmall-Q8_0.gguf"),
            new ModelLink("Whisper Small", R.string.model_desc_whisper_small, "257 MB",
                    "https://huggingface.co/handy-computer/whisper-small-gguf/resolve/main/whisper-small-Q8_0.gguf"),
            new ModelLink("Nemotron 3.5 Streaming 0.6B", R.string.model_desc_nemotron, "473 MB",
                    "https://huggingface.co/handy-computer/nemotron-3.5-asr-streaming-0.6b-gguf/resolve/main/nemotron-3.5-asr-streaming-0.6b-Q4_K_M.gguf"),
            new ModelLink("Parakeet TDT 0.6B v3 Q8", R.string.model_desc_parakeet_v3_q8, "740 MB",
                    "https://huggingface.co/handy-computer/parakeet-tdt-0.6b-v3-gguf/resolve/main/parakeet-tdt-0.6b-v3-Q8_0.gguf"),
            new ModelLink("Whisper Large-v3-Turbo", R.string.model_desc_whisper_turbo, "845 MB",
                    "https://huggingface.co/handy-computer/whisper-large-v3-turbo-gguf/resolve/main/whisper-large-v3-turbo-Q8_0.gguf"),
            new ModelLink("huggingface.co/handy-computer", R.string.model_desc_browse, "",
                    "https://huggingface.co/handy-computer"),
    };

    private LinearLayout modelList;
    private TextView statusText;
    private View importArea;
    private ProgressBar importBar;
    private TextView importText;
    private Button importButton;
    private Spinner languageSpinner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_models);

        modelList = findViewById(R.id.model_list);
        statusText = findViewById(R.id.txt_model_status);
        importArea = findViewById(R.id.import_area);
        importBar = findViewById(R.id.import_progress);
        importText = findViewById(R.id.txt_import);
        importButton = findViewById(R.id.btn_import);
        languageSpinner = findViewById(R.id.spinner_language);

        importButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQ_PICK_MODEL);
        });

        findViewById(R.id.btn_model_links).setOnClickListener(v -> showLinksDialog());

        setupLanguageSpinner();
        setupThreadsSpinner();

        com.google.android.material.materialswitch.MaterialSwitch translateSwitch =
                findViewById(R.id.switch_translate);
        translateSwitch.setChecked(!readConfig("model_translate").isEmpty());
        translateSwitch.setOnCheckedChangeListener((btn, checked) -> {
            writeConfig("model_translate", checked ? "1" : "");
            statusText.setText(getString(R.string.models_loading));
            reloadModelNative(this);
        });

        refreshList();
    }

    // --- Language selection -------------------------------------------------

    /**
     * Locales offered in the language dropdown. The empty tag is the model's
     * automatic mode; display names come from {@link Locale#getDisplayName()},
     * so they follow the device language without needing translations. Tags a
     * model doesn't support are degraded by the engine at run time.
     */
    private static final String[] LANGUAGE_TAGS = {
            "", "bg-BG", "hr-HR", "cs-CZ", "da-DK", "nl-NL", "en-US", "en-GB",
            "et-EE", "fi-FI", "fr-FR", "de-DE", "el-GR", "hu-HU", "it-IT",
            "lv-LV", "lt-LT", "mt-MT", "pl-PL", "pt-PT", "pt-BR", "ro-RO",
            "ru-RU", "sk-SK", "sl-SI", "es-ES", "sv-SE", "uk-UA",
            "ar-SA", "zh-CN", "hi-IN", "ja-JP", "ko-KR", "tr-TR",
    };

    private void setupLanguageSpinner() {
        String stored = readConfig("model_language");

        List<String> tags = new ArrayList<>(Arrays.asList(LANGUAGE_TAGS));
        // A value from an older version (free-text field) stays selectable.
        if (!stored.isEmpty() && !tags.contains(stored)) {
            tags.add(stored);
        }

        List<String> labels = new ArrayList<>(tags.size());
        for (String tag : tags) {
            labels.add(tag.isEmpty()
                    ? getString(R.string.models_language_auto)
                    : Locale.forLanguageTag(tag).getDisplayName() + " (" + tag + ")");
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        languageSpinner.setAdapter(adapter);
        languageSpinner.setSelection(Math.max(0, tags.indexOf(stored)), false);
        languageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String tag = tags.get(position);
                if (tag.equals(readConfig("model_language"))) return;
                writeConfig("model_language", tag);
                snackbar(getString(R.string.models_language_saved));
                statusText.setText(getString(R.string.models_loading));
                reloadModelNative(ModelsActivity.this);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    // --- Inference threads --------------------------------------------------

    /**
     * CPU thread count for inference, stored in {@code model_threads}. Empty =
     * automatic (the engine derives it from the CPU topology). Offered values
     * cover the realistic range on phones; the engine treats anything invalid
     * as automatic.
     */
    private void setupThreadsSpinner() {
        Spinner spinner = findViewById(R.id.spinner_threads);
        String stored = readConfig("model_threads");

        List<String> values = new ArrayList<>(
                Arrays.asList("", "1", "2", "3", "4", "5", "6", "8"));
        if (!stored.isEmpty() && !values.contains(stored)) {
            values.add(stored);
        }

        List<String> labels = new ArrayList<>(values.size());
        for (String v : values) {
            labels.add(v.isEmpty() ? getString(R.string.models_threads_auto) : v);
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, values.indexOf(stored)), false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String value = values.get(position);
                if (value.equals(readConfig("model_threads"))) return;
                writeConfig("model_threads", value);
                statusText.setText(getString(R.string.models_loading));
                reloadModelNative(ModelsActivity.this);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    // --- Model list -------------------------------------------------------

    private File modelsDir() {
        File dir = new File(getFilesDir(), "models");
        dir.mkdirs();
        return dir;
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

    private void writeConfig(String name, String value) {
        File f = new File(getFilesDir(), name);
        if (value == null || value.isEmpty()) {
            f.delete();
            return;
        }
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(value.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.e(TAG, "Failed to write " + name, e);
        }
    }

    private void refreshList() {
        modelList.removeAllViews();
        String active = readConfig("active_model");

        List<String> names = new ArrayList<>();
        File[] files = modelsDir().listFiles(
                (dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".gguf"));
        if (files != null) {
            for (File f : files) names.add(f.getName());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);

        // If the active model's file has disappeared, fall back to built-in.
        if (!active.isEmpty() && !names.contains(active)) {
            writeConfig("active_model", "");
            active = "";
        }

        addModelRow(getString(R.string.models_builtin),
                getString(R.string.models_builtin_sub), null, active.isEmpty());
        for (String name : names) {
            String size = Formatter.formatShortFileSize(
                    this, new File(modelsDir(), name).length());
            addModelRow(name, size, name, name.equals(active));
        }
    }

    /** Adds one selectable row; {@code fileName} is null for the built-in model. */
    private void addModelRow(String title, String subtitle, String fileName, boolean checked) {
        View row = getLayoutInflater().inflate(R.layout.item_model, modelList, false);
        ((TextView) row.findViewById(R.id.txt_model_name)).setText(title);
        ((TextView) row.findViewById(R.id.txt_model_sub)).setText(subtitle);

        RadioButton radio = row.findViewById(R.id.radio_model);
        radio.setChecked(checked);
        row.setOnClickListener(v -> {
            if (!checked) selectModel(fileName);
        });

        ImageButton delete = row.findViewById(R.id.btn_model_delete);
        if (fileName != null) {
            delete.setOnClickListener(v -> confirmDelete(fileName));
        } else {
            delete.setVisibility(View.GONE);
        }

        modelList.addView(row);
    }

    private void selectModel(String fileNameOrNull) {
        writeConfig("active_model", fileNameOrNull == null ? "" : fileNameOrNull);
        refreshList();
        statusText.setText(getString(R.string.models_loading));
        reloadModelNative(this);
    }

    private void confirmDelete(String fileName) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.models_delete)
                .setMessage(getString(R.string.models_delete_confirm, fileName))
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    new File(modelsDir(), fileName).delete();
                    if (fileName.equals(readConfig("active_model"))) {
                        selectModel(null);
                    } else {
                        refreshList();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // --- Download links ----------------------------------------------------

    private void showLinksDialog() {
        ArrayAdapter<ModelLink> adapter = new ArrayAdapter<ModelLink>(
                this, R.layout.item_model_link, MODEL_LINKS) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View row = convertView != null ? convertView
                        : getLayoutInflater().inflate(R.layout.item_model_link, parent, false);
                ModelLink link = MODEL_LINKS[position];
                ((TextView) row.findViewById(R.id.txt_link_name)).setText(link.name);
                ((TextView) row.findViewById(R.id.txt_link_size)).setText(link.size);
                ((TextView) row.findViewById(R.id.txt_link_desc)).setText(link.descRes);
                return row;
            }
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.models_links_title)
                .setAdapter(adapter, (d, which) -> {
                    Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(MODEL_LINKS[which].url));
                    try {
                        startActivity(view);
                    } catch (android.content.ActivityNotFoundException e) {
                        snackbar(getString(R.string.models_no_browser));
                    }
                })
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    // --- Import ------------------------------------------------------------

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_MODEL || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;

        String name = queryDisplayName(uri);
        long size = querySize(uri);

        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".gguf")) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.models_import_bad_title)
                    .setMessage(R.string.models_import_bad_body)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        if (size > 0 && getFilesDir().getUsableSpace() < size + FREE_SPACE_MARGIN) {
            snackbar(getString(R.string.models_import_no_space));
            return;
        }

        importModel(uri, name, size);
    }

    private void importModel(Uri uri, String name, long size) {
        importButton.setEnabled(false);
        importArea.setVisibility(View.VISIBLE);
        importBar.setIndeterminate(size <= 0);
        importBar.setMax(100);
        importBar.setProgress(0);
        importText.setText(getString(R.string.models_importing, name));

        File dest = new File(modelsDir(), name);
        File tmp = new File(modelsDir(), name + ".part");

        new Thread(() -> {
            boolean ok = false;
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[1024 * 1024];
                long copied = 0;
                int read;
                while (in != null && (read = in.read(buf)) != -1) {
                    out.write(buf, 0, read);
                    copied += read;
                    if (size > 0) {
                        int pct = (int) (copied * 100 / size);
                        runOnUiThread(() -> importBar.setProgress(pct));
                    }
                }
                ok = true;
            } catch (IOException e) {
                Log.e(TAG, "Model import failed", e);
            }

            boolean success = ok && tmp.renameTo(dest);
            if (!success) tmp.delete();
            runOnUiThread(() -> {
                importButton.setEnabled(true);
                importArea.setVisibility(View.GONE);
                if (success) {
                    snackbar(getString(R.string.models_import_done, name));
                    refreshList();
                } else {
                    snackbar(getString(R.string.models_import_failed));
                }
            });
        }, "model-import").start();
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to query display name", e);
        }
        return uri.getLastPathSegment();
    }

    private long querySize(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to query size", e);
        }
        return -1;
    }

    private void snackbar(String message) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG).show();
    }

    // Called from Rust with load progress ("Loading model...", "Ready", "Error: ...").
    public void onStatusUpdate(String status) {
        runOnUiThread(() -> statusText.setText(status));
    }

    private native void reloadModelNative(ModelsActivity activity);
}
