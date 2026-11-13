package dev.notune.transcribe;

import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.app.ProgressDialog;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Live-subtitle start screen. The user picks the translation
 * target here — "Auto (original language)" keeps the spoken language, a fixed
 * tag arms the on-device translator at the next session — and then starts the
 * captioning flow (overlay permission → screen-capture consent → service).
 * Keeping the selector on this screen leaves MainActivity free of
 * subtitle-specific settings.
 */
public class LiveSubtitleActivity extends AppCompatActivity {
    private static final String TAG = "LiveSubtitleActivity";
    private MediaProjectionManager mProjectionManager;
    private boolean mWaitingForOverlayPermission = false;
    private boolean mProjectionStarted = false;
    private boolean mTranslationScreenInitialized = false;
    private ProgressDialog mTranslationDownloadDialog;
    private OnDeviceSubtitleTranslator mModelPreloader;
    private String mPreviousTranslationTarget = SubtitleTranslationTargets.AUTO;
    private boolean mPreparingTranslationModel = false;

    private final ActivityResultLauncher<Intent> projectionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                    Toast.makeText(this, getString(R.string.subs_capture_denied), Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }

                Intent serviceIntent = new Intent(this, LiveSubtitleService.class);
                serviceIntent.setAction(LiveSubtitleService.ACTION_START);
                serviceIntent.putExtra("code", result.getResultCode());
                serviceIntent.putExtra("data", result.getData());

                startForegroundService(serviceIntent);
                finish();
            });

    @Override
    public void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);

        mProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        // Request overlay permission before showing the translation settings.
        if (Settings.canDrawOverlays(this)) {
            showTranslationScreen();
        } else {
            // Defer launching Settings until the activity has entered its
            // foreground, so onResume does not mistake the initial launch
            // for a return from the permission screen.
            getWindow().getDecorView().post(() -> {
                if (isFinishing()) {
                    return;
                }
                if (Settings.canDrawOverlays(this)) {
                    showTranslationScreen();
                } else {
                    mWaitingForOverlayPermission = true;
                    openOverlaySettings();
                }
            });
        }
    }

    private void showTranslationScreen() {
        if (mTranslationScreenInitialized) {
            return;
        }
        mTranslationScreenInitialized = true;

        setContentView(R.layout.activity_live_subtitle);

        View rootView = findViewById(R.id.live_subtitle_root);
        if (rootView != null) {
            ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, windowInsets) -> {
                Insets insets = windowInsets.getInsets(
                        WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
                );
                v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
                return windowInsets;
            });
        }

        setupSubtitleTranslationSpinner(findViewById(R.id.spinner_subtitle_translation));
        findViewById(R.id.btn_live_subs_start).setOnClickListener(v -> beginSubtitleFlow());
    }

    /** Entry of the permission flow: overlay permission first, then projection. */
    private void beginSubtitleFlow() {
        if (Settings.canDrawOverlays(this)) {
            startProjection();
        } else {
            mWaitingForOverlayPermission = true;
            openOverlaySettings();
        }
    }

    private void openOverlaySettings() {
        Toast.makeText(this, getString(R.string.subs_grant_overlay), Toast.LENGTH_LONG).show();

        try {
            // Use the specific app overlay settings page
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open overlay settings", e);
            // Fallback: open app settings
            try {
                Intent appSettings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()));
                appSettings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(appSettings);
                Toast.makeText(this, getString(R.string.subs_grant_overlay), Toast.LENGTH_LONG).show();
            } catch (Exception e2) {
                Log.e(TAG, "Failed to open app settings", e2);
                Toast.makeText(this, getString(R.string.subs_overlay_required), Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Show translation settings only after overlay permission is granted.
        if (mWaitingForOverlayPermission) {
            mWaitingForOverlayPermission = false;
            if (Settings.canDrawOverlays(this)) {
                showTranslationScreen();
            } else {
                Toast.makeText(this, getString(R.string.subs_overlay_required), Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private void startProjection() {
        if (mProjectionStarted) {
            return;
        }
        mProjectionStarted = true;
        projectionLauncher.launch(mProjectionManager.createScreenCaptureIntent());
    }

    /**
     * Downloads the selected target's ML Kit language model before saving the
     * preference. The UI remains blocked by a non-cancelable progress dialog,
     * but the download itself runs asynchronously (never on the UI thread).
     */
    private void prepareTranslationModel(String targetTag, Spinner spinner,
                                         View startButton, List<String> tags) {
        String targetCode = SubtitleTranslationTargets.mlKitCode(targetTag);
        if (targetCode == null) {
            restoreTranslationSelection(spinner, tags);
            return;
        }

        // If the speech model declares its language, use it as the source.
        // Otherwise English is used to ensure the target pack is downloaded;
        // a different detected source can be prepared by ML Kit when needed.
        String sourceCode = readModelLanguageForPreload();
        if (sourceCode == null) sourceCode = SubtitleTranslationTargets.mlKitCode("en");

        mPreparingTranslationModel = true;
        spinner.setEnabled(false);
        startButton.setEnabled(false);
        mTranslationDownloadDialog = new ProgressDialog(this);
        mTranslationDownloadDialog.setIndeterminate(true);
        mTranslationDownloadDialog.setCancelable(false);
        mTranslationDownloadDialog.setMessage(getString(R.string.subtitle_translation_downloading));
        mTranslationDownloadDialog.show();

        if (mModelPreloader == null) {
            mModelPreloader = new OnDeviceSubtitleTranslator();
        }
        mModelPreloader.prepareModel(sourceCode, targetCode,
                new OnDeviceSubtitleTranslator.ModelDownloadCallback() {
                    @Override
                    public void onReady() {
                        if (isFinishing() || isDestroyed()) return;
                        finishTranslationPreparation(targetTag, spinner, startButton, true);
                    }

                    @Override
                    public void onFailure(Throwable error) {
                        Log.w(TAG, "Could not download translation model for " + targetTag, error);
                        if (isFinishing() || isDestroyed()) return;
                        finishTranslationPreparation(targetTag, spinner, startButton, false);
                    }
                });
    }

    private String readModelLanguageForPreload() {
        java.io.File file = new java.io.File(getFilesDir(), "model_language");
        if (!file.exists()) return SubtitleTranslationTargets.mlKitCode("en");
        try {
            String tag = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            String code = SubtitleTranslationTargets.mlKitCode(tag);
            return code != null ? code : SubtitleTranslationTargets.mlKitCode("en");
        } catch (java.io.IOException error) {
            Log.w(TAG, "Could not read speech model language; using English for model preparation", error);
            return SubtitleTranslationTargets.mlKitCode("en");
        }
    }

    private void finishTranslationPreparation(String targetTag, Spinner spinner,
                                              View startButton, boolean success) {
        mPreparingTranslationModel = false;
        spinner.setEnabled(true);
        startButton.setEnabled(true);
        if (mTranslationDownloadDialog != null && mTranslationDownloadDialog.isShowing()) {
            mTranslationDownloadDialog.dismiss();
        }

        if (success) {
            SubtitlePrefs.setTranslationTarget(this, targetTag);
            mPreviousTranslationTarget = targetTag;
            Snackbar.make(findViewById(android.R.id.content),
                    getString(R.string.subtitle_translation_saved), Snackbar.LENGTH_LONG).show();
        } else {
            restoreTranslationSelection(spinner, SubtitleTranslationTargets.getTags());
            Snackbar.make(findViewById(android.R.id.content),
                    getString(R.string.subtitle_translation_download_failed), Snackbar.LENGTH_LONG).show();
        }
    }

    private void restoreTranslationSelection(Spinner spinner, List<String> tags) {
        int previousPosition = tags.indexOf(mPreviousTranslationTarget);
        spinner.setSelection(Math.max(0, previousPosition), false);
    }

    @Override
    protected void onDestroy() {
        if (mTranslationDownloadDialog != null && mTranslationDownloadDialog.isShowing()) {
            mTranslationDownloadDialog.dismiss();
        }
        if (mModelPreloader != null) {
            mModelPreloader.cancelAll();
            mModelPreloader = null;
        }
        super.onDestroy();
    }

    /**
     * Live-subtitle translation target. "Auto (original
     * language)" keeps the spoken language (the product decision for this
     * feature); a fixed target translates finalized subtitles on-device.
     * Display names come from {@link Locale#getDisplayName()}, so they follow
     * the device language without needing translations — same pattern as the
     * Models screen's language dropdown.
     */
    private void setupSubtitleTranslationSpinner(Spinner spinner) {
        List<String> tags = SubtitleTranslationTargets.getTags();
        List<String> labels = new ArrayList<>(tags.size());
        for (String tag : tags) {
            labels.add(SubtitleTranslationTargets.AUTO.equals(tag)
                    ? getString(R.string.subtitle_translation_auto)
                    : Locale.forLanguageTag(tag).getDisplayName() + " (" + tag + ")");
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        String stored = SubtitlePrefs.getTranslationTarget(this);
        mPreviousTranslationTarget = stored;
        spinner.setSelection(Math.max(0, tags.indexOf(stored)), false);
        View startButton = findViewById(R.id.btn_live_subs_start);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (mPreparingTranslationModel) return;
                String tag = tags.get(position);
                if (tag.equals(mPreviousTranslationTarget)) return;

                if (SubtitleTranslationTargets.AUTO.equals(tag)) {
                    SubtitlePrefs.setTranslationTarget(LiveSubtitleActivity.this, tag);
                    mPreviousTranslationTarget = tag;
                    Snackbar.make(findViewById(android.R.id.content),
                            getString(R.string.subtitle_translation_saved), Snackbar.LENGTH_LONG).show();
                    return;
                }

                prepareTranslationModel(tag, spinner, startButton, tags);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }
}
