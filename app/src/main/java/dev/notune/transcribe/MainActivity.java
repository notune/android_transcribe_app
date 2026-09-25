package dev.notune.transcribe;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

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
    private Button voiceTryButton;
    private Button startSubsButton;
    private Button benchButton;
    private TextView benchResultText;

    // --- Tabs: Home, Style, History, Settings (floating tab bar) ---------------------
    private static final String STATE_TAB = "tab";
    private static final int[] TAB_PAGES = {
            R.id.page_home, R.id.page_style, R.id.page_history, R.id.page_settings};
    private static final int[] TAB_ICONS = {
            R.drawable.ic_home, R.drawable.ic_palette, R.drawable.ic_history, R.drawable.ic_settings};
    private static final int[] TAB_LABELS = {
            R.string.tab_home, R.string.tab_style, R.string.history_title, R.string.section_settings};
    /** Tab width at natural size, the gap on each side, and the pill's inner padding. */
    private static final int TAB_ITEM_DP = 76;
    private static final int TAB_ITEM_GAP_DP = 2;
    private static final int TAB_BAR_PAD_DP = 6;
    /** Screen margin on each side when the pill has to shrink. */
    private static final int TAB_BAR_MARGIN_DP = 16;
    /** Below this tab width, labels hide and only icons show. */
    private static final int MIN_LABELLED_TAB_DP = 48;
    private final View[] tabItems = new View[TAB_PAGES.length];
    private int currentTab;
    private StylePage stylePage;
    private HistoryPage historyPage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.text_status);
        voiceStatusText = findViewById(R.id.text_voice_status);
        voiceStatusIcon = findViewById(R.id.img_voice_status);
        voiceGrantButton = findViewById(R.id.btn_voice_grant);
        voiceTryButton = findViewById(R.id.btn_voice_try);
        startSubsButton = findViewById(R.id.btn_subs_start);
        Button imeSettingsButton = findViewById(R.id.btn_ime_settings);
        Button voiceHelpButton = findViewById(R.id.btn_voice_help);

        voiceGrantButton.setOnClickListener(v -> checkAndRequestPermissions());
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

        findViewById(R.id.btn_models).setOnClickListener(v ->
                startActivity(new Intent(this, ModelsActivity.class)));

        findViewById(R.id.btn_custom_words).setOnClickListener(v ->
                startActivity(new Intent(this, CustomWordsActivity.class)));

        setupBubbleSection();
        setupBubbleUnloadRadio();
        stylePage = new StylePage(this, findViewById(R.id.page_style));
        historyPage = new HistoryPage(this, findViewById(R.id.page_history));
        setupRetentionRadio();
        setupTabs(savedInstanceState != null ? savedInstanceState.getInt(STATE_TAB, 0) : 0);

        benchButton = findViewById(R.id.btn_benchmark);
        benchResultText = findViewById(R.id.text_bench_result);
        benchButton.setOnClickListener(v -> runBenchmark());

        // Settings stored as marker files in filesDir (readable from the :ime
        // process and native code without a content provider).
        bindMarkerSwitch(R.id.switch_auto_record, "auto_record", false);
        bindMarkerSwitch(R.id.switch_select_transcription, "select_transcription", false);
        bindMarkerSwitch(R.id.switch_pause_audio, "pause_audio", false);
        // Record-in-background defaults to ON; its marker file is the opt-out.
        bindMarkerSwitch(R.id.switch_record_background, "stop_on_hide", true);
        bindMarkerSwitch(R.id.switch_auto_stop, "auto_stop", false);

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
                // The Flow bubble follows this setting too.
                InsertionAccessibilityService.notifyPrefsChanged();
            }
        });

        // Initial check
        updateVoiceInputStatus();

        // Start init
        initNative(this);
    }

    /** Builds the floating tinted-blur tab bar and shows {@code initial}. */
    private void setupTabs(int initial) {
        BackdropBlurView bar = findViewById(R.id.tab_bar);
        float density = getResources().getDisplayMetrics().density;
        bar.setSource(findViewById(R.id.pages), 24 * density);
        bar.setCornerRadius(32 * density);
        int surface = themeColor(com.google.android.material.R.attr.colorSurfaceContainer);
        // Tinted blur: the theme's surface colour over a real backdrop blur.
        bar.setTint(androidx.core.graphics.ColorUtils.setAlphaComponent(surface, 0xB8),
                androidx.core.graphics.ColorUtils.setAlphaComponent(surface, 0xF2));

        // Natural size: fixed-width tabs. On a screen narrower than that, the
        // pill takes the screen minus margins and the tabs share it evenly;
        // labels shrink (autosize) and, only when a tab gets tiny, hide.
        int naturalDp = TAB_PAGES.length * (TAB_ITEM_DP + 2 * TAB_ITEM_GAP_DP) + 2 * TAB_BAR_PAD_DP;
        int availableDp = getResources().getConfiguration().screenWidthDp - 2 * TAB_BAR_MARGIN_DP;
        boolean narrow = availableDp < naturalDp;
        int itemDp = narrow
                ? (availableDp - 2 * TAB_BAR_PAD_DP) / TAB_PAGES.length - 2 * TAB_ITEM_GAP_DP
                : TAB_ITEM_DP;
        if (narrow) {
            android.widget.FrameLayout.LayoutParams lp =
                    (android.widget.FrameLayout.LayoutParams) bar.getLayoutParams();
            lp.width = Math.round(availableDp * density);
            bar.setLayoutParams(lp);
        }

        android.view.ViewGroup items = findViewById(R.id.tab_items);
        android.view.LayoutInflater inflater = getLayoutInflater();
        for (int i = 0; i < TAB_PAGES.length; i++) {
            View item = inflater.inflate(R.layout.item_tab, items, false);
            ((ImageView) item.findViewById(R.id.tab_icon)).setImageResource(TAB_ICONS[i]);
            TextView label = item.findViewById(R.id.tab_label);
            label.setText(TAB_LABELS[i]);
            if (itemDp < MIN_LABELLED_TAB_DP) label.setVisibility(View.GONE);
            item.setContentDescription(getString(TAB_LABELS[i]));
            android.view.ViewGroup.LayoutParams ilp = item.getLayoutParams();
            ilp.width = Math.round(itemDp * density);
            item.setLayoutParams(ilp);
            final int index = i;
            item.setOnClickListener(v -> selectTab(index));
            items.addView(item);
            tabItems[i] = item;
        }
        selectTab(initial);
        hideTabBarWithKeyboard(bar);
    }

    /**
     * Hides the tab bar while the on-screen keyboard is open, as navigation bars
     * usually do, so it never sits between the keyboard and the Flow bubble.
     */
    private void hideTabBarWithKeyboard(View bar) {
        View root = findViewById(android.R.id.content);
        android.graphics.Rect visible = new android.graphics.Rect();
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            root.getWindowVisibleDisplayFrame(visible);
            int screen = root.getRootView().getHeight();
            boolean keyboard = screen - visible.bottom > screen * 0.15f;
            bar.setVisibility(keyboard ? View.GONE : View.VISIBLE);
        });
    }

    private void selectTab(int index) {
        for (int i = 0; i < tabItems.length; i++) tabItems[i].setSelected(i == index);
        showPage(index);
    }

    private void showPage(int index) {
        currentTab = index;
        for (int i = 0; i < TAB_PAGES.length; i++) {
            findViewById(TAB_PAGES[i]).setVisibility(i == index ? View.VISIBLE : View.GONE);
        }
        if (TAB_PAGES[index] == R.id.page_style) stylePage.refresh();
        if (TAB_PAGES[index] == R.id.page_history) historyPage.loadHistory();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // Keeps the tab across the recreate that an Appearance change triggers.
        outState.putInt(STATE_TAB, currentTab);
    }

    @Override
    public void onBackPressed() {
        if (currentTab != 0) {
            selectTab(0);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-check on return from the keyboard chooser, settings, or a test run.
        updateVoiceInputStatus();
        updateBubbleStatus();
    }

    /**
     * Reflects whether the primary voice-input flow is ready. Apps (SwiftKey,
     * Firefox, …) fire {@link RecognizerIntent#ACTION_RECOGNIZE_SPEECH} via
     * {@code startActivityForResult}, which {@link RecognizeActivity} handles.
     * The flow works when (a) the mic permission is granted and (b) our activity
     * is what Android resolves that intent to — either because it is the sole
     * handler or because the user picked us as the default. This is deliberately
     * judged on the activity path, NOT on being the default {@code RecognitionService}.
     */
    private void updateVoiceInputStatus() {
        boolean micGranted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;

        if (!micGranted) {
            setVoiceStatus(false, getString(R.string.voice_status_need_mic));
            voiceGrantButton.setVisibility(View.VISIBLE);
            voiceTryButton.setEnabled(false);
            return;
        }

        voiceGrantButton.setVisibility(View.GONE);
        voiceTryButton.setEnabled(true);

        if (isOurAppDefaultRecognizer()) {
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

    private void setupBubbleSection() {
        findViewById(R.id.btn_bubble_mic).setOnClickListener(v -> checkAndRequestPermissions());
        findViewById(R.id.btn_bubble_a11y).setOnClickListener(v -> showA11yConsent());
        findViewById(R.id.btn_bubble_end_snooze).setOnClickListener(v -> {
            BubblePrefs.setSnoozeUntil(this, 0);
            InsertionAccessibilityService.notifyPrefsChanged();
            updateBubbleStatus();
        });

        CompoundButton sw = findViewById(R.id.switch_bubble);
        sw.setChecked(BubblePrefs.isEnabled(this));
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            BubblePrefs.setEnabled(this, isChecked);
            InsertionAccessibilityService.notifyPrefsChanged();
        });

        CompoundButton search = findViewById(R.id.switch_bubble_hide_search);
        search.setChecked(BubblePrefs.isHideInSearch(this));
        search.setOnCheckedChangeListener((buttonView, isChecked) -> {
            BubblePrefs.setHideInSearch(this, isChecked);
            InsertionAccessibilityService.notifyPrefsChanged();
        });

        CompoundButton noKeyboard = findViewById(R.id.switch_bubble_no_keyboard);
        noKeyboard.setChecked(BubblePrefs.isShowWithoutKeyboard(this));
        noKeyboard.setOnCheckedChangeListener((buttonView, isChecked) -> {
            BubblePrefs.setShowWithoutKeyboard(this, isChecked);
            InsertionAccessibilityService.notifyPrefsChanged();
        });
    }

    /**
     * Prominent disclosure before the user turns on the accessibility service.
     * Consent is stored first; the bubble stays off until both consent and the
     * running service are present.
     */
    private void showA11yConsent() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.a11y_consent_title)
                .setMessage(R.string.a11y_consent_body)
                .setPositiveButton(R.string.a11y_consent_accept, (d, w) -> {
                    BubblePrefs.setA11yConsent(this, true);
                    InsertionAccessibilityService.notifyPrefsChanged();
                    if (!InsertionAccessibilityService.isRunning()) {
                        InsertionAccessibilityService.openSettings(this);
                    }
                    updateBubbleStatus();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Refreshes the setup steps and the snooze row of the Flow bubble card. */
    private void updateBubbleStatus() {
        boolean micGranted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        setStep(R.id.img_bubble_mic, R.id.text_bubble_mic, R.id.btn_bubble_mic, micGranted,
                getString(micGranted ? R.string.bubble_mic_ok : R.string.bubble_mic_needed));

        boolean a11yActive = InsertionAccessibilityService.isRunning()
                && BubblePrefs.hasA11yConsent(this);
        setStep(R.id.img_bubble_a11y, R.id.text_bubble_a11y, R.id.btn_bubble_a11y, a11yActive,
                getString(a11yActive ? R.string.bubble_a11y_active : R.string.bubble_a11y_inactive));

        long until = BubblePrefs.getSnoozeUntil(this);
        View row = findViewById(R.id.row_bubble_snooze);
        if (until > System.currentTimeMillis()) {
            String time = android.text.format.DateFormat.getTimeFormat(this)
                    .format(new java.util.Date(until));
            ((TextView) findViewById(R.id.text_bubble_snooze))
                    .setText(getString(R.string.bubble_snoozed_until, time));
            row.setVisibility(View.VISIBLE);
        } else {
            row.setVisibility(View.GONE);
        }
    }

    private void setStep(int iconId, int textId, int buttonId, boolean ok, String message) {
        ImageView icon = findViewById(iconId);
        icon.setImageResource(ok ? R.drawable.ic_check_circle : R.drawable.ic_error);
        int tint = ok
                ? ContextCompat.getColor(this, R.color.status_ok)
                : themeColor(com.google.android.material.R.attr.colorError);
        ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(tint));
        ((TextView) findViewById(textId)).setText(message);
        findViewById(buttonId).setVisibility(ok ? View.GONE : View.VISIBLE);
    }

    private void setupRetentionRadio() {
        RadioGroup rg = findViewById(R.id.rg_retention);
        int retention = HistoryPrefs.getRetention(this);
        switch (retention) {
            case 0: rg.check(R.id.rb_ret_off); break;
            case 10: rg.check(R.id.rb_ret_10); break;
            case 25: rg.check(R.id.rb_ret_25); break;
            case 50: rg.check(R.id.rb_ret_50); break;
            case 100: rg.check(R.id.rb_ret_100); break;
            default: rg.check(R.id.rb_ret_unlimited); break;
        }
        rg.setOnCheckedChangeListener((group, checkedId) -> {
            int val;
            if (checkedId == R.id.rb_ret_off) val = 0;
            else if (checkedId == R.id.rb_ret_10) val = 10;
            else if (checkedId == R.id.rb_ret_25) val = 25;
            else if (checkedId == R.id.rb_ret_50) val = 50;
            else if (checkedId == R.id.rb_ret_100) val = 100;
            else val = -1;
            HistoryPrefs.setRetention(this, val);
            TranscriptionHistory.get(this).prune();
            historyPage.loadHistory();
        });
    }

    private void setupBubbleUnloadRadio() {
        RadioGroup rg = findViewById(R.id.rg_bubble_unload);
        switch (BubblePrefs.getUnloadMinutes(this)) {
            case 0: rg.check(R.id.rb_unload_never); break;
            case 5: rg.check(R.id.rb_unload_5); break;
            case 30: rg.check(R.id.rb_unload_30); break;
            default: rg.check(R.id.rb_unload_15); break;
        }
        rg.setOnCheckedChangeListener((group, checkedId) -> {
            int val;
            if (checkedId == R.id.rb_unload_never) val = 0;
            else if (checkedId == R.id.rb_unload_5) val = 5;
            else if (checkedId == R.id.rb_unload_30) val = 30;
            else val = 15;
            BubblePrefs.setUnloadMinutes(this, val);
            notifyBubbleRefresh();
        });
    }

    /** Tells a visible bubble to re-read its settings (size / unload interval). */
    private void notifyBubbleRefresh() {
        InsertionAccessibilityService.notifyPrefsChanged();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERM_REQ_CODE) {
            updateVoiceInputStatus();
            updateBubbleStatus();
        }
    }

    // --- Benchmark ----------------------------------------------------------

    /**
     * Transcribes the bundled test clip through the active model and shows the
     * speed as a real-time factor. The clip is a fixed English recording so
     * results are comparable across models and devices; the run uses the
     * current model settings (language hint, translate).
     */
    private void runBenchmark() {
        benchButton.setEnabled(false);
        benchResultText.setVisibility(View.VISIBLE);
        benchResultText.setText(R.string.bench_running);

        new Thread(() -> {
            float[] samples;
            try {
                samples = readWavAsset("bench.wav");
            } catch (IOException e) {
                Log.e(TAG, "Failed to read benchmark clip", e);
                runOnUiThread(() -> {
                    benchResultText.setText(getString(R.string.bench_error, e.getMessage()));
                    benchButton.setEnabled(true);
                });
                return;
            }
            benchmarkNative(this, samples, samples.length);
        }, "benchmark-load").start();
    }

    // Called from Rust when the benchmark run finishes.
    public void onBenchmarkResult(float audioSecs, float computeSecs, String error) {
        runOnUiThread(() -> {
            benchButton.setEnabled(true);
            benchResultText.setVisibility(View.VISIBLE);
            if (error != null && !error.isEmpty()) {
                benchResultText.setText(getString(R.string.bench_error, error));
            } else {
                benchResultText.setText(getString(R.string.bench_result,
                        String.format(java.util.Locale.getDefault(), "%.1f", audioSecs),
                        String.format(java.util.Locale.getDefault(), "%.1f", computeSecs),
                        String.format(java.util.Locale.getDefault(), "%.1f",
                                computeSecs > 0 ? audioSecs / computeSecs : 0)));
            }
        });
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
            statusText.setText(status);
            // "Ready" may carry a suffix, e.g. "Ready (this model can't translate)".
            if (status.startsWith("Ready")) {
                startSubsButton.setEnabled(true);
            }
        });
    }

    private native void initNative(MainActivity activity);

    private native void benchmarkNative(MainActivity activity, float[] samples, int length);
}
