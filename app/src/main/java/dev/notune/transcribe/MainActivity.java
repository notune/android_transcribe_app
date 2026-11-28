package dev.notune.transcribe;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Filter;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int PERM_REQ_CODE = 101;
    private static final int REQ_VOICE_TEST = 202;

    static {
        try {
            System.loadLibrary("c++_shared");
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "Failed to load c++_shared", e);
        }
        System.loadLibrary("android_transcribe_app");
    }

    private TextView statusText;
    private TextView voiceStatusText;
    private ImageView voiceStatusIcon;
    private Button voiceGrantButton;
    private Button voiceAddModelButton;
    private Button voiceTryButton;
    private Button startSubsButton;
    private Button benchButton;
    private TextView benchResultText;
    private Button benchDetailsButton;
    private Button modelsButton;
    private boolean benchRunning;
    /** Bumped on every run and on screen exit; stale callbacks are dropped. */
    private int benchGeneration;
    private PostProcessor.Task benchPostTask;
    private long benchPostStartedAt;
    private String benchTranscript;
    private String benchCleanedText;
    /** Whether the fields at run start could be persisted; the key may not. */
    private boolean benchSettingsSaved;

    private CompoundButton postProcessingSwitch;
    private TextInputEditText ppEndpoint;
    private TextInputEditText ppModel;
    private TextInputEditText ppKey;
    private TextInputLayout ppEndpointLayout;
    private TextInputLayout ppModelLayout;
    private TextInputLayout ppKeyLayout;
    private TextView ppStatus;
    private TextView ppWarning;
    private Button ppTestButton;
    private Button ppModelsButton;
    private PostProcessor.Task ppTestTask;
    private PostProcessor.Task ppModelsTask;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.text_status);
        voiceStatusText = findViewById(R.id.text_voice_status);
        voiceStatusIcon = findViewById(R.id.img_voice_status);
        voiceGrantButton = findViewById(R.id.btn_voice_grant);
        voiceAddModelButton = findViewById(R.id.btn_voice_add_model);
        voiceTryButton = findViewById(R.id.btn_voice_try);
        startSubsButton = findViewById(R.id.btn_subs_start);
        Button imeSettingsButton = findViewById(R.id.btn_ime_settings);
        Button voiceHelpButton = findViewById(R.id.btn_voice_help);

        voiceGrantButton.setOnClickListener(v -> checkAndRequestPermissions());
        voiceAddModelButton.setOnClickListener(v ->
                startActivity(new Intent(this, ModelsActivity.class)));
        voiceTryButton.setOnClickListener(v -> launchVoiceTest());
        voiceHelpButton.setOnClickListener(v -> showHelpDialog());

        imeSettingsButton.setOnClickListener(v -> {
             Intent intent = new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS);
             startActivity(intent);
        });

        startSubsButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, LiveSubtitleActivity.class);
            startActivity(intent);
        });

        findViewById(R.id.btn_subs_advanced).setOnClickListener(v -> showSubsAdvancedDialog());
        if (!LiveSubtitleService.isSupported()) {
            // Capturing other apps' audio needs Android 10.
            startSubsButton.setEnabled(false);
            findViewById(R.id.btn_subs_advanced).setVisibility(View.GONE);
            ((TextView) findViewById(R.id.text_subs_desc)).setText(R.string.subs_unsupported);
        }

        modelsButton = findViewById(R.id.btn_models);
        modelsButton.setOnClickListener(v ->
                startActivity(new Intent(this, ModelsActivity.class)));
        if (!ModelStore.hasBuiltinModel(this)) {
            ((TextView) findViewById(R.id.text_models_desc)).setText(R.string.models_desc_no_builtin);
        }

        benchButton = findViewById(R.id.btn_benchmark);
        benchResultText = findViewById(R.id.text_bench_result);
        benchDetailsButton = findViewById(R.id.btn_bench_details);
        benchButton.setOnClickListener(v -> runBenchmark());
        benchDetailsButton.setOnClickListener(v -> showBenchDetails());

        // Settings stored as marker files in filesDir (readable from the
        // keyboard service and native code).
        bindMarkerSwitch(R.id.switch_auto_record, "auto_record", false);
        bindMarkerSwitch(R.id.switch_select_transcription, "select_transcription", false);
        bindMarkerSwitch(R.id.switch_pause_audio, "pause_audio", false);
        // Record-in-background defaults to ON; its marker file is the opt-out.
        bindMarkerSwitch(R.id.switch_record_background, "stop_on_hide", true);
        bindMarkerSwitch(R.id.switch_auto_stop, "auto_stop", false);

        bindPostProcessingSettings();

        // Live subtitle line limit: 2 (default), 4, or 0 = unlimited.
        RadioGroup subsLinesGroup = findViewById(R.id.rg_subtitle_lines);
        int subsLines = SubtitlePrefs.getMaxLines(this);
        if (subsLines == 4) {
            subsLinesGroup.check(R.id.rb_subs_4);
        } else if (subsLines == 0) {
            subsLinesGroup.check(R.id.rb_subs_all);
        } else {
            subsLinesGroup.check(R.id.rb_subs_2);
        }
        subsLinesGroup.setOnCheckedChangeListener((group, checkedId) -> {
            int lines = checkedId == R.id.rb_subs_4 ? 4
                    : checkedId == R.id.rb_subs_all ? 0 : 2;
            SubtitlePrefs.setMaxLines(this, lines);
        });

        RadioGroup themeGroup = findViewById(R.id.rg_theme);
        switch (ThemePrefs.getMode(this)) {
            case AppCompatDelegate.MODE_NIGHT_NO:
                themeGroup.check(R.id.rb_theme_light);
                break;
            case AppCompatDelegate.MODE_NIGHT_YES:
                themeGroup.check(R.id.rb_theme_dark);
                break;
            default:
                themeGroup.check(R.id.rb_theme_system);
                break;
        }
        themeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            int newMode;
            if (checkedId == R.id.rb_theme_light) {
                newMode = AppCompatDelegate.MODE_NIGHT_NO;
            } else if (checkedId == R.id.rb_theme_dark) {
                newMode = AppCompatDelegate.MODE_NIGHT_YES;
            } else {
                newMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
            }
            if (newMode != ThemePrefs.getMode(this)) {
                ThemePrefs.setMode(this, newMode);
            }
        });

        // Initial check
        updateVoiceInputStatus();
    }

    @Override
    protected void onPause() {
        // The keyboard service reads these settings from the same process'
        // preferences, so flush edits before the user switches to a field.
        savePostProcessingSettings();
        super.onPause();
    }

    @Override
    protected void onStop() {
        // The screen is no longer visible: drop any model-list request so a
        // late answer cannot open a dialog or change the saved model.
        cancelModelList();
        // Same for the speed test: a late answer must not update the card
        // after the user has left.
        cancelBenchmark();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (ppTestTask != null) {
            ppTestTask.cancel();
            ppTestTask = null;
        }
        cancelModelList();
        cancelBenchmark();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-check on return from the keyboard chooser, settings, or a test run.
        updateVoiceInputStatus();
        updateModelState();
        // (Re)load the engine: on first start, and after models were added or
        // removed. Reports "Ready" right away if it is already loaded.
        initNative(this);
    }

    /**
     * Without any speech model (F-Droid builds start without one), nothing
     * that transcribes can work: say so in red and lead to the models screen.
     */
    private void updateModelState() {
        boolean hasModel = ModelStore.hasAnyModel(this);
        findViewById(R.id.text_no_model).setVisibility(hasModel ? View.GONE : View.VISIBLE);
        statusText.setVisibility(hasModel ? View.VISIBLE : View.GONE);
        modelsButton.setText(hasModel ? R.string.btn_models : R.string.btn_add_model);
        benchButton.setEnabled(hasModel && !benchRunning);
        startSubsButton.setEnabled(hasModel && LiveSubtitleService.isSupported());
    }

    /**
     * Reflects whether the primary voice-input flow is ready. Apps (SwiftKey,
     * Firefox, …) fire {@link RecognizerIntent#ACTION_RECOGNIZE_SPEECH} via
     * {@code startActivityForResult}, which {@link RecognizeActivity} handles.
     * The flow works when (a) the mic permission is granted, (b) a speech model
     * is available and (c) our activity
     * is what Android resolves that intent to — either because it is the sole
     * handler or because the user picked us as the default. This is deliberately
     * judged on the activity path, NOT on being the default {@code RecognitionService}.
     */
    private void updateVoiceInputStatus() {
        boolean micGranted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;

        boolean hasModel = ModelStore.hasAnyModel(this);
        voiceAddModelButton.setVisibility(micGranted && !hasModel ? View.VISIBLE : View.GONE);

        if (!micGranted) {
            setVoiceStatus(false, getString(R.string.voice_status_need_mic));
            voiceGrantButton.setVisibility(View.VISIBLE);
            voiceTryButton.setEnabled(false);
            return;
        }

        voiceGrantButton.setVisibility(View.GONE);
        voiceTryButton.setEnabled(hasModel);

        if (!hasModel) {
            setVoiceStatus(false, getString(R.string.voice_status_need_model));
        } else if (isOurAppDefaultRecognizer()) {
            setVoiceStatus(true, getString(R.string.voice_status_ready));
        } else {
            setVoiceStatus(false, getString(R.string.voice_status_almost));
        }
    }

    /** True if our RecognizeActivity is what RECOGNIZE_SPEECH resolves to. */
    private boolean isOurAppDefaultRecognizer() {
        Intent recog = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        ResolveInfo resolved = getPackageManager()
                .resolveActivity(recog, PackageManager.MATCH_DEFAULT_ONLY);
        return resolved != null && resolved.activityInfo != null
                && getPackageName().equals(resolved.activityInfo.packageName);
    }

    private void setVoiceStatus(boolean ready, String message) {
        voiceStatusText.setText(message);
        voiceStatusIcon.setImageResource(ready ? R.drawable.ic_check_circle : R.drawable.ic_error);
        int tint = ready
                ? ContextCompat.getColor(this, R.color.status_ok)
                : themeColor(com.google.android.material.R.attr.colorError);
        ImageViewCompat.setImageTintList(voiceStatusIcon, ColorStateList.valueOf(tint));
        voiceStatusIcon.setContentDescription(message);
    }

    private int themeColor(int attrRes) {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(attrRes, tv, true);
        return tv.resourceId != 0 ? ContextCompat.getColor(this, tv.resourceId) : tv.data;
    }

    /**
     * One-tap self-test: fires the exact intent a keyboard's mic does. If ours is
     * the sole handler it launches straight away; if several apps handle it the
     * system shows a chooser, where the user can pick us and "Always" — which sets
     * the default and flips the status to ready on return.
     */
    private void launchVoiceTest() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        try {
            startActivityForResult(intent, REQ_VOICE_TEST);
        } catch (android.content.ActivityNotFoundException e) {
            Log.w(TAG, "No RECOGNIZE_SPEECH handler", e);
            snackbar(getString(R.string.voice_test_failed));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VOICE_TEST && resultCode == RESULT_OK && data != null) {
            ArrayList<String> results =
                    data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            String heard = (results != null && !results.isEmpty()) ? results.get(0) : null;
            snackbar(heard != null && !heard.trim().isEmpty()
                    ? getString(R.string.voice_test_ok, heard)
                    : getString(R.string.voice_test_empty));
        }
        // onResume() also refreshes, but do it here too for an immediate update.
        updateVoiceInputStatus();
    }

    /**
     * Explains the adb escape hatch for the MediaProjection consent dialog:
     * once the PROJECT_MEDIA app-op is set to allow, the system permission
     * activity returns RESULT_OK immediately, so subtitles start without the
     * "Start recording or casting?" sheet. No app code depends on this — the
     * normal dialog flow is the untouched fallback.
     */
    private void showSubsAdvancedDialog() {
        String allowCmd = "adb shell appops set --user 0 " + getPackageName()
                + " PROJECT_MEDIA allow";
        String resetCmd = "adb shell appops set --user 0 " + getPackageName()
                + " PROJECT_MEDIA default";
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.subs_advanced_title)
                .setMessage(getString(R.string.subs_advanced_body, allowCmd, resetCmd))
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.subs_advanced_copy, (d, w) -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("adb", allowCmd));
                    snackbar(getString(R.string.subs_advanced_copied));
                })
                .show();
    }

    private void showHelpDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.voice_help_title)
                .setMessage(R.string.voice_help_body)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void snackbar(String message) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG).show();
    }

    // --- Optional text post-processing ---------------------------------------

    private void bindPostProcessingSettings() {
        postProcessingSwitch = findViewById(R.id.switch_post_processing);
        ppEndpoint = findViewById(R.id.input_pp_endpoint);
        ppModel = findViewById(R.id.input_pp_model);
        ppKey = findViewById(R.id.input_pp_key);
        ppEndpointLayout = findViewById(R.id.layout_pp_endpoint);
        ppModelLayout = findViewById(R.id.layout_pp_model);
        ppKeyLayout = findViewById(R.id.layout_pp_key);
        ppStatus = findViewById(R.id.text_pp_status);
        ppWarning = findViewById(R.id.text_pp_warning);
        ppTestButton = findViewById(R.id.btn_pp_test);

        ppEndpoint.setText(PostProcessingSettings.getEndpoint(this));
        ppModel.setText(PostProcessingSettings.getModel(this));
        String key = PostProcessingSettings.getApiKey(this);
        ppKey.setText(key == null ? "" : key);

        postProcessingSwitch.setChecked(PostProcessingSettings.isEnabled(this));
        postProcessingSwitch.setOnCheckedChangeListener((button, checked) -> {
            PostProcessingSettings.setEnabled(this, checked);
            updatePostProcessingUi();
        });
        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                ppEndpointLayout.setError(null);
                ppModelLayout.setError(null);
                updatePostProcessingUi();
            }
        };
        ppEndpoint.addTextChangedListener(watcher);
        ppModel.addTextChangedListener(watcher);

        findViewById(R.id.btn_pp_prompt).setOnClickListener(v -> showPromptDialog());
        ppTestButton.setOnClickListener(v -> testPostProcessing());
        ppModelsButton = findViewById(R.id.btn_pp_models);
        ppModelsButton.setOnClickListener(v -> fetchModelList());
        updatePostProcessingUi();
    }

    /** Persists the fields. The API key goes to the Keystore, not to prefs. */
    private boolean savePostProcessingSettings() {
        if (ppEndpoint == null) return true;
        PostProcessingSettings.setEndpoint(this, ppEndpoint.getText() == null
                ? "" : ppEndpoint.getText().toString());
        PostProcessingSettings.setModel(this, ppModel.getText() == null
                ? "" : ppModel.getText().toString());
        int keyState = PostProcessingSettings.setApiKey(this, ppKey.getText() == null
                ? "" : ppKey.getText().toString());
        if (ppKeyLayout != null) {
            if (keyState == PostProcessingSettings.KEY_INVALID) {
                ppKeyLayout.setError(getString(R.string.pp_key_invalid));
            } else if (keyState == PostProcessingSettings.KEY_STORE_FAILED) {
                ppKeyLayout.setError(getString(R.string.pp_key_store_failed));
            } else {
                ppKeyLayout.setError(null);
            }
        }
        return keyState == PostProcessingSettings.KEY_SAVED;
    }

    private void updatePostProcessingUi() {
        if (ppStatus == null) return;
        boolean enabled = postProcessingSwitch != null && postProcessingSwitch.isChecked();
        String endpoint = ppEndpoint == null || ppEndpoint.getText() == null
                ? "" : ppEndpoint.getText().toString().trim();
        String model = ppModel == null || ppModel.getText() == null
                ? "" : ppModel.getText().toString().trim();

        if (!enabled) {
            ppStatus.setText(R.string.pp_status_off);
        } else if (endpoint.isEmpty() || model.isEmpty()) {
            ppStatus.setText(R.string.pp_status_needs_config);
        } else {
            ppStatus.setText(R.string.pp_status_on);
        }
        ppWarning.setVisibility(enabled && PostProcessingLogic.isCleartextEndpoint(endpoint)
                ? View.VISIBLE : View.GONE);
    }

    private void showPromptDialog() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(getResources().getDisplayMetrics().density * 20);
        container.setPadding(pad, Math.round(getResources().getDisplayMetrics().density * 8), pad, 0);

        TextView note = new TextView(this);
        note.setText(R.string.pp_prompt_note);
        note.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        note.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
        container.addView(note);

        EditText edit = new EditText(this);
        edit.setText(PostProcessingSettings.getPrompt(this));
        edit.setGravity(Gravity.TOP | Gravity.START);
        edit.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        edit.setMinLines(14);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(edit, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        container.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.round(getResources().getDisplayMetrics().density * 360)));

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pp_prompt_title)
                .setView(container)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    PostProcessingSettings.setPrompt(this, edit.getText().toString());
                    snackbar(getString(R.string.pp_prompt_saved));
                })
                .setNeutralButton(R.string.pp_prompt_reset, (dialog, which) -> {
                    edit.setText(PostProcessingSettings.defaultPrompt(this));
                    PostProcessingSettings.resetPrompt(this);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Sends one synthetic phrase through the configured server. Proves the
     * address, key and model work without dictating into someone's field.
     */
    private void testPostProcessing() {
        if (!savePostProcessingSettings()) {
            return;
        }
        String endpoint = PostProcessingSettings.getEndpoint(this);
        String model = PostProcessingSettings.getModel(this);

        if (endpoint.isEmpty()) {
            ppEndpointLayout.setError(getString(R.string.pp_invalid_address));
            return;
        }
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            ppEndpointLayout.setError(getString(R.string.pp_invalid_address));
            return;
        }
        if (model.isEmpty()) {
            ppModelLayout.setError(getString(R.string.pp_model_required));
            return;
        }

        ppTestButton.setEnabled(false);
        ppTestButton.setText(R.string.pp_testing);
        final String sample = getString(R.string.pp_test_sample);
        ppTestTask = PostProcessor.run(this, sample, (text, error) -> {
            ppTestTask = null;
            ppTestButton.setEnabled(true);
            ppTestButton.setText(R.string.pp_test);
            if (error != null) {
                showPostProcessingDialog(getString(R.string.pp_test_failed,
                        PostProcessingLogic.shortError(error)));
            } else if (text == null || text.trim().isEmpty()
                    || PostProcessingLogic.isFillerOnly(text)) {
                showPostProcessingDialog(getString(R.string.pp_test_empty));
            } else {
                showPostProcessingDialog(getString(R.string.pp_test_ok, sample, text));
            }
        });
    }

    /**
     * Fetches the server's model list and lets the user pick one. The manual
     * field stays the source of truth; the list only fills it.
     *
     * <p>The same button cancels a request in flight, so the user always has a
     * visible way out without leaving the screen.
     */
    private void fetchModelList() {
        if (ppModelsTask != null) {
            cancelModelList();
            return;
        }
        if (!savePostProcessingSettings()) {
            return;
        }
        String endpoint = PostProcessingSettings.getEndpoint(this);
        if (endpoint.isEmpty()
                || (!endpoint.startsWith("http://") && !endpoint.startsWith("https://"))) {
            ppEndpointLayout.setError(getString(R.string.pp_invalid_address));
            return;
        }
        ppStatus.setText(R.string.pp_models_loading);
        ppModelsButton.setText(R.string.pp_models_cancel);
        ppModelsTask = PostProcessor.listModels(this, (models, error) -> {
            ppModelsTask = null;
            ppModelsButton.setText(R.string.pp_models_fetch);
            updatePostProcessingUi();
            if (error != null) {
                showPostProcessingDialog(getString(R.string.pp_models_failed,
                        PostProcessingLogic.shortError(error)));
                return;
            }
            if (models == null || models.isEmpty()) {
                showPostProcessingDialog(getString(R.string.pp_models_empty));
                return;
            }
            if (models.size() == 1) {
                applyModel(models.get(0));
                return;
            }
            showModelDialog(models);
        });
    }

    /**
     * Cancels a model-list request and restores the button. Safe when no
     * request is running and safe to call twice, as onStop and onDestroy may
     * both run.
     */
    private void cancelModelList() {
        if (ppModelsTask != null) {
            ppModelsTask.cancel();
            ppModelsTask = null;
        }
        if (ppModelsButton != null) {
            ppModelsButton.setText(R.string.pp_models_fetch);
        }
        updatePostProcessingUi();
    }

    private void applyModel(String model) {
        ppModel.setText(model);
        PostProcessingSettings.setModel(this, model);
        ppModelLayout.setError(null);
        updatePostProcessingUi();
    }

    private void showModelDialog(List<String> models) {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(density * 20);
        container.setPadding(pad, Math.round(density * 8), pad, 0);

        EditText filter = new EditText(this);
        filter.setHint(R.string.pp_models_search);
        filter.setSingleLine(true);
        filter.setInputType(InputType.TYPE_CLASS_TEXT);
        container.addView(filter);

        List<ModelEntry> entries = new ArrayList<>();
        String current = PostProcessingSettings.getModel(this);
        if (!current.isEmpty() && !models.contains(current)) {
            entries.add(new ModelEntry(current,
                    getString(R.string.pp_models_current, current)));
        }
        for (String model : models) {
            entries.add(new ModelEntry(model, model));
        }
        final List<ModelEntry> allEntries = new ArrayList<>(entries);
        ArrayAdapter<ModelEntry> adapter = new ArrayAdapter<ModelEntry>(this,
                android.R.layout.simple_list_item_1, new ArrayList<>(allEntries)) {
            private final Filter substringFilter = new Filter() {
                @Override
                protected FilterResults performFiltering(CharSequence constraint) {
                    String query = constraint == null ? "" : constraint.toString();
                    List<ModelEntry> matched = new ArrayList<>();
                    for (ModelEntry entry : allEntries) {
                        // The default ArrayAdapter filter matches only the start
                        // of a word, so "alpha" would miss "vendor/alpha-chat".
                        if (PostProcessingLogic.matchesModelQuery(entry.id, query)) {
                            matched.add(entry);
                        }
                    }
                    FilterResults results = new FilterResults();
                    results.values = matched;
                    results.count = matched.size();
                    return results;
                }

                @Override
                protected void publishResults(CharSequence constraint, FilterResults results) {
                    setNotifyOnChange(false);
                    clear();
                    if (results.values instanceof List) {
                        for (Object item : (List<?>) results.values) {
                            if (item instanceof ModelEntry) {
                                add((ModelEntry) item);
                            }
                        }
                    }
                    notifyDataSetChanged();
                }
            };

            @Override
            public Filter getFilter() {
                return substringFilter;
            }
        };
        ListView list = new ListView(this);
        list.setAdapter(adapter);
        container.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.round(density * 320)));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pp_models_title)
                .setView(container)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        filter.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                adapter.getFilter().filter(s == null ? "" : s.toString());
            }
        });
        list.setOnItemClickListener((parent, view, position, id) -> {
            Object item = parent.getItemAtPosition(position);
            if (item instanceof ModelEntry) {
                applyModel(((ModelEntry) item).id);
            }
            dialog.dismiss();
        });
        dialog.show();
    }

    /** One row in the model dialog: the label may differ from the value. */
    private static final class ModelEntry {
        final String id;
        private final String label;

        ModelEntry(String id, String label) {
            this.id = id;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private void showPostProcessingDialog(String message) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pp_test_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /**
     * Binds a switch to a marker file in filesDir. With {@code inverted}, the
     * file's presence means the switch is OFF (used for default-on settings).
     */
    private void bindMarkerSwitch(int switchId, String fileName, boolean inverted) {
        CompoundButton sw = findViewById(switchId);
        File marker = new File(getFilesDir(), fileName);
        sw.setChecked(marker.exists() != inverted);
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            boolean shouldExist = isChecked != inverted;
            if (shouldExist) {
                try {
                    marker.createNewFile();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to create " + fileName + " file", e);
                }
            } else {
                marker.delete();
            }
        });
    }

    private void checkAndRequestPermissions() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, PERM_REQ_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERM_REQ_CODE) {
            updateVoiceInputStatus();
        }
    }

    // --- Benchmark ----------------------------------------------------------

    /**
     * Runs the bundled Russian clip through the active model and, when text
     * cleaning is switched on and configured, through the same post-processor
     * the normal dictation uses. Shows recognition time, real-time factor,
     * cleaning time and the total.
     *
     * <p>Audio never leaves the device. Only the recognised text is sent, and
     * only when cleaning is on, exactly as in a normal dictation.
     */
    private void runBenchmark() {
        if (benchRunning) return;
        // Judge the fields as they are right now. Endpoint, model and key are
        // otherwise persisted only when the screen pauses, so a freshly typed
        // address would be ignored and a cleared one would still be used.
        benchSettingsSaved = savePostProcessingSettings();
        cancelBenchmark();
        int generation = benchGeneration;
        benchRunning = true;
        benchTranscript = null;
        benchCleanedText = null;
        benchButton.setEnabled(false);
        benchDetailsButton.setVisibility(View.GONE);
        benchResultText.setVisibility(View.VISIBLE);
        benchResultText.setText(R.string.bench_running);

        new Thread(() -> {
            float[] samples;
            try {
                samples = readWavAsset("bench.wav");
            } catch (IOException e) {
                Log.e(TAG, "Failed to read benchmark clip", e);
                runOnUiThread(() -> {
                    if (!benchRunning || generation != benchGeneration) return;
                    finishBenchmarkError(getString(R.string.bench_error, e.getMessage()));
                });
                return;
            }
            benchmarkNative(this, samples, samples.length, generation);
        }, "benchmark-load").start();
    }

    /**
     * Called from Rust when recognition finishes. {@code generation} is the
     * run that produced the answer: a late result from a cancelled run is
     * dropped instead of overwriting a newer one.
     */
    public void onBenchmarkResult(float audioSecs, float computeSecs, String transcript,
                                  String error, long generation) {
        runOnUiThread(() -> {
            if (!benchRunning || generation != benchGeneration) return;
            benchResultText.setVisibility(View.VISIBLE);
            if (error != null && !error.isEmpty()) {
                finishBenchmarkError(getString(R.string.bench_error, error));
                return;
            }

            benchTranscript = transcript == null ? "" : transcript.trim();
            String base = getString(R.string.bench_result_base,
                    seconds(audioSecs), seconds(computeSecs),
                    seconds(BenchmarkLogic.realTimeFactor(audioSecs, computeSecs)));

            if (!BenchmarkLogic.hasTranscript(benchTranscript)) {
                finishBenchmark(base + "\n" + getString(R.string.bench_no_text));
                return;
            }

            // The key field changed but could not be stored: do not send the
            // text with the previous key still in the Keystore.
            if (!benchSettingsSaved && PostProcessingSettings.isEnabled(this)) {
                finishBenchmark(base + "\n" + getString(R.string.pp_key_store_failed));
                return;
            }

            int stage = BenchmarkLogic.cleaningStage(
                    PostProcessingSettings.isEnabled(this),
                    PostProcessingSettings.getEndpoint(this),
                    PostProcessingSettings.getModel(this));
            if (stage == BenchmarkLogic.CLEANING_DISABLED) {
                finishBenchmark(base + "\n" + getString(R.string.bench_skip_disabled));
                return;
            }
            if (stage == BenchmarkLogic.CLEANING_NOT_CONFIGURED) {
                finishBenchmark(base + "\n" + getString(R.string.bench_skip_unconfigured));
                return;
            }

            benchResultText.setText(base + "\n" + getString(R.string.bench_cleaning_running));
            benchPostStartedAt = SystemClock.elapsedRealtime();
            benchPostTask = PostProcessor.run(this, benchTranscript, (text, postError) -> {
                benchPostTask = null;
                if (!benchRunning || generation != benchGeneration) return;
                double postSecs =
                        (SystemClock.elapsedRealtime() - benchPostStartedAt) / 1000.0;
                String withCleaning = base + "\n"
                        + getString(R.string.bench_cleaning_line, seconds(postSecs));
                if (postError != null) {
                    finishBenchmark(withCleaning + "\n"
                            + getString(R.string.bench_pp_failed,
                                    PostProcessingLogic.shortError(postError)));
                    return;
                }
                if (text == null || text.trim().isEmpty()) {
                    finishBenchmark(withCleaning + "\n" + getString(R.string.bench_pp_empty));
                    return;
                }
                benchCleanedText = text;
                finishBenchmark(withCleaning + "\n"
                        + getString(R.string.bench_total_line,
                                seconds(BenchmarkLogic.totalSeconds(computeSecs, postSecs))));
            });
        });
    }

    /** Ends a run normally: recognition succeeded, cleaning may have been skipped. */
    private void finishBenchmark(String result) {
        benchRunning = false;
        benchPostTask = null;
        benchButton.setEnabled(ModelStore.hasAnyModel(this));
        benchResultText.setVisibility(View.VISIBLE);
        benchResultText.setText(result);
        benchDetailsButton.setVisibility(
                BenchmarkLogic.hasTranscript(benchTranscript) ? View.VISIBLE : View.GONE);
    }

    /** Ends a run with a short reason; no texts are offered. */
    private void finishBenchmarkError(String message) {
        benchRunning = false;
        benchButton.setEnabled(ModelStore.hasAnyModel(this));
        benchResultText.setVisibility(View.VISIBLE);
        benchResultText.setText(message);
        benchDetailsButton.setVisibility(View.GONE);
    }

    /**
     * Drops a run in flight. The native recognition cannot be interrupted, so
     * its late answer is filtered by {@code benchGeneration}; the cleaning
     * request is disconnected and its callback suppressed by PostProcessor.
     */
    private void cancelBenchmark() {
        benchGeneration++;
        if (benchPostTask != null) {
            benchPostTask.cancel();
            benchPostTask = null;
        }
        if (benchRunning) {
            benchRunning = false;
            benchResultText.setVisibility(View.VISIBLE);
            benchResultText.setText(R.string.bench_cancelled);
            benchButton.setEnabled(ModelStore.hasAnyModel(this));
            benchDetailsButton.setVisibility(View.GONE);
        }
    }

    private static String seconds(double value) {
        return String.format(java.util.Locale.getDefault(), "%.1f", value);
    }

    /** Shows the recognised and cleaned texts in a selectable dialog. */
    private void showBenchDetails() {
        if (!BenchmarkLogic.hasTranscript(benchTranscript)) return;
        String message = benchCleanedText == null
                ? getString(R.string.bench_details_one, benchTranscript)
                : getString(R.string.bench_details_text, benchTranscript, benchCleanedText);

        TextView text = new TextView(this);
        text.setText(message);
        text.setTextIsSelectable(true);
        int pad = Math.round(getResources().getDisplayMetrics().density * 20);
        text.setPadding(pad, 0, pad, 0);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.bench_details_title)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /**
     * Reads a 16 kHz mono 16-bit PCM WAV from assets into float samples. Only
     * handles the format the bundled clip is stored in; no general WAV support.
     */
    private float[] readWavAsset(String name) throws IOException {
        byte[] bytes;
        try (java.io.InputStream in = getAssets().open(name);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[64 * 1024];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
            bytes = out.toByteArray();
        }

        // Find the "data" chunk instead of assuming a 44-byte header.
        int offset = 12;
        while (offset + 8 <= bytes.length) {
            int chunkSize = (bytes[offset + 4] & 0xff) | ((bytes[offset + 5] & 0xff) << 8)
                    | ((bytes[offset + 6] & 0xff) << 16) | ((bytes[offset + 7] & 0xff) << 24);
            if (bytes[offset] == 'd' && bytes[offset + 1] == 'a'
                    && bytes[offset + 2] == 't' && bytes[offset + 3] == 'a') {
                int start = offset + 8;
                int count = Math.min(chunkSize, bytes.length - start) / 2;
                float[] samples = new float[count];
                for (int i = 0; i < count; i++) {
                    int lo = bytes[start + 2 * i] & 0xff;
                    int hi = bytes[start + 2 * i + 1];
                    samples[i] = ((hi << 8) | lo) / 32768.0f;
                }
                return samples;
            }
            offset += 8 + chunkSize + (chunkSize & 1);
        }
        throw new IOException("no data chunk in " + name);
    }

    // Called from Rust
    public void onStatusUpdate(String status) {
        runOnUiThread(() -> {
            statusText.setText(ModelStore.displayStatus(this, status));
            // "Ready" may carry a suffix, e.g. "Ready (this model can't translate)".
            if (status.startsWith("Ready") && LiveSubtitleService.isSupported()) {
                startSubsButton.setEnabled(true);
            }
        });
    }

    private native void initNative(MainActivity activity);

    private native void benchmarkNative(MainActivity activity, float[] samples, int length,
                                        long generation);
}
