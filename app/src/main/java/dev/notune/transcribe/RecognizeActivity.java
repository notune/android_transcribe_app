package dev.notune.transcribe;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.util.Log;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.content.pm.PackageManager;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;

public class RecognizeActivity extends AppCompatActivity {

    private static final String TAG = "OfflineVoiceInput";
    /** How long a keyboard gets to insert the result before it is copied. */
    private static final long CLIPBOARD_DELAY_MS = 1000;

    static {
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("android_transcribe_app");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native libraries", e);
        }
    }

    private TextView status;
    private boolean isRecording = false;
    // Set between stopping the capture and delivering (or dropping) the text.
    // Leaving the panel in this window must invalidate the dictation before a
    // late transcript can start a network request.
    private boolean awaitingTranscript = false;
    // False as soon as the panel is closing or the dictation is discarded.
    // Late native callbacks check it before doing any work.
    private boolean dictationActive = false;
    private MicLevelView micLevel;
    private final AudioFocusPauser audioPauser = new AudioFocusPauser();
    private boolean pauseAudioActive = false;
    private PostProcessor.Task postProcessingTask;
    private boolean resultDelivered = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.recognize_activity);

        // Keep the screen awake for the lifetime of this recording screen so it
        // never sleeps mid-capture and cuts the recording short.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        micLevel = findViewById(R.id.mic_level);
        status = findViewById(R.id.txt_status);

        findViewById(R.id.btn_close).setOnClickListener(v -> {
            // discard current recording and any late result
            dictationActive = false;
            awaitingTranscript = false;
            if (isRecording) {
                isRecording = false;
                cancelRecording();   // new native method
            }
            if (postProcessingTask != null) {
                postProcessingTask.cancel();
                postProcessingTask = null;
            }
            if (pauseAudioActive) {
                audioPauser.abandon(this);
                pauseAudioActive = false;
            }
            setResult(Activity.RESULT_CANCELED);
            finish();
        });

        // Tap anywhere (or on mic) to stop
        findViewById(R.id.root).setOnClickListener(v -> finishRecording());

        // Permission check
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            status.setText(R.string.voice_need_mic);
            return;
        }

        // Nothing to transcribe with yet: tapping the panel opens the screen
        // that adds a model.
        if (!ModelStore.hasAnyModel(this)) {
            status.setText(R.string.voice_no_model);
            findViewById(R.id.root).setOnClickListener(v -> {
                startActivity(new Intent(this, ModelsActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                setResult(Activity.RESULT_CANCELED);
                finish();
            });
            return;
        }

        initNative(this);
        isRecording = true;
        dictationActive = true;
        status.setText(R.string.voice_listening);
        if (isPauseAudioEnabled()) {
            audioPauser.request(this);
            pauseAudioActive = true;
        }
        startRecording(isAutoStopEnabled());
    }

    /** Stop capture and transcribe — used by both tap-to-stop and auto-stop. */
    private void finishRecording() {
        if (!isRecording) return;
        isRecording = false;
        awaitingTranscript = true;
        status.setText(R.string.voice_processing);
        stopRecording();
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
    }

    // Called from Rust (monitor thread) when trailing silence is detected.
    public void onAutoStop() {
        runOnUiThread(this::finishRecording);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // The popup is no longer visible (user switched apps or went home).
        // It runs in its own task (singleTask), so it would otherwise keep
        // recording invisibly in the background and never reappear. Discard
        // and close so the next mic tap starts fresh. (Background recording
        // is a keyboard-only feature; a popup must not record unseen.)
        //
        // This also covers the gap between stopping the capture and the
        // transcript arriving: without invalidating the dictation here, a late
        // native callback could still start a network request after the panel
        // is gone.
        if (!isFinishing()) {
            boolean busy = isRecording || awaitingTranscript || postProcessingTask != null;
            if (busy) {
                dictationActive = false;
                awaitingTranscript = false;
                if (isRecording) {
                    isRecording = false;
                    try { cancelRecording(); } catch (Throwable t) { /* ignore */ }
                }
                if (postProcessingTask != null) {
                    postProcessingTask.cancel();
                    postProcessingTask = null;
                }
                if (pauseAudioActive) {
                    audioPauser.abandon(this);
                    pauseAudioActive = false;
                }
                setResult(Activity.RESULT_CANCELED);
                finish();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        dictationActive = false;
        awaitingTranscript = false;
        if (postProcessingTask != null) {
            postProcessingTask.cancel();
            postProcessingTask = null;
        }
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
        try { cleanupNative(this); } catch (Throwable t) { /* ignore */ }
    }

    // Called from Rust
    public void onStatusUpdate(String s) {
        runOnUiThread(() -> {
            // Errors are shown as they are; everything else (model loading,
            // "Transcribing...") just means listening or busy for the user.
            if (s != null && s.startsWith("Error")) {
                status.setText(ModelStore.displayStatus(this, s));
            } else if (postProcessingTask != null) {
                // A late "Transcribing"/"Ready" from the engine must not hide
                // the fact that the text is still being cleaned up.
                status.setText(R.string.pp_processing);
            } else {
                status.setText(isRecording ? R.string.voice_listening : R.string.voice_processing);
            }
        });
    }

    // Called from Rust with 0..1
    public void onAudioLevel(float level) {
        runOnUiThread(() -> micLevel.setLevel(level));
    }

    // Called from Rust – keep same method name as IME for code reuse
    public void onTextTranscribed(String text) {
        runOnUiThread(() -> {
            if (!dictationActive || isFinishing() || isDestroyed()) {
                // The panel was closed or left while the engine was still
                // transcribing; the result belongs to a discarded dictation.
                return;
            }
            if (text == null || text.trim().isEmpty()) {
                finishWithoutSpeech();
                return;
            }

            String raw = text.trim();
            if (!PostProcessingSettings.isEnabled(this)) {
                deliverResult(raw);
                return;
            }
            if (!PostProcessingSettings.isConfigured(this)) {
                Toast.makeText(getApplicationContext(), R.string.pp_not_configured,
                        Toast.LENGTH_LONG).show();
                deliverResult(raw);
                return;
            }

            status.setText(R.string.pp_processing);
            postProcessingTask = PostProcessor.run(this, raw, (cleaned, error) -> {
                postProcessingTask = null;
                if (!dictationActive || isFinishing() || isDestroyed() || resultDelivered) return;

                if (error != null) {
                    Toast.makeText(getApplicationContext(),
                            getString(R.string.pp_failed_fallback,
                                    PostProcessingLogic.shortError(error)),
                            Toast.LENGTH_LONG).show();
                    deliverResult(raw);
                    return;
                }
                boolean usable = cleaned != null && !cleaned.trim().isEmpty()
                        && !PostProcessingLogic.isFillerOnly(cleaned);
                if (!usable) {
                    if (PostProcessingLogic.isFillerOnly(raw)) {
                        finishWithoutSpeech();
                    } else {
                        Toast.makeText(getApplicationContext(), R.string.pp_empty_fallback,
                                Toast.LENGTH_LONG).show();
                        deliverResult(raw);
                    }
                    return;
                }
                deliverResult(cleaned.trim());
            });
        });
    }

    private void finishWithoutSpeech() {
        // Nothing was recognized (e.g. auto-stop after silence only).
        // Say so: otherwise the panel just vanishes as if it broke.
        dictationActive = false;
        awaitingTranscript = false;
        Toast.makeText(getApplicationContext(), R.string.voice_no_speech,
                Toast.LENGTH_SHORT).show();
        setResult(Activity.RESULT_CANCELED);
        finish();
    }

    private void deliverResult(String text) {
        if (resultDelivered) return;
        resultDelivered = true;
        dictationActive = false;
        awaitingTranscript = false;
        Intent request = getIntent();
        // Search boxes (e.g. Chrome's omnibox) want a query, not a sentence: a
        // trailing period would turn "example.com" into a search for "example.com.".
        if (RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH.equals(
                request.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL))
                && text.endsWith(".") && !text.endsWith("..")) {
            text = text.substring(0, text.length() - 1);
        }

        ArrayList<String> results = new ArrayList<>();
        results.add(text);

        Intent data = new Intent();
        data.putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, results);
        // Callers such as Chrome's voice search drop results that come without
        // one confidence score per hypothesis. The engine has no calibrated
        // score, so the single hypothesis is reported as confident.
        data.putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, new float[]{1.0f});

        // Callers may ask for the results via a PendingIntent instead of the
        // activity result (part of the RecognizerIntent contract).
        PendingIntent resultsIntent = request.getParcelableExtra(
                RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT);
        if (resultsIntent != null) {
            Intent fillIn = new Intent();
            Bundle extra = request.getBundleExtra(
                    RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT_BUNDLE);
            if (extra != null) fillIn.putExtras(extra);
            fillIn.putExtras(data);
            try {
                resultsIntent.send(this, Activity.RESULT_OK, fillIn);
            } catch (PendingIntent.CanceledException e) {
                Log.w(TAG, "Results PendingIntent was cancelled", e);
            }
        }

        // Keyboards (e.g. SwiftKey's voice key) insert the result themselves
        // once their field reconnects after this panel closes, and sometimes
        // lose it in that handover. Keep a copy on the clipboard so it can
        // still be pasted, but only after the keyboard had its chance:
        // Android 13+ shows a clipboard preview on copy, which would
        // otherwise pop up in the middle of that handover.
        if (isKeyboard(getCallingPackage())) {
            String copy = text;
            Context app = getApplicationContext();
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> copyToClipboard(app, copy), CLIPBOARD_DELAY_MS);
        }

        setResult(Activity.RESULT_OK, data);
        finish();
    }

    private boolean isKeyboard(String pkg) {
        if (pkg == null) return false;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm == null) return false;
        for (InputMethodInfo info : imm.getEnabledInputMethodList()) {
            if (pkg.equals(info.getPackageName())) return true;
        }
        return false;
    }

    private static void copyToClipboard(Context context, String text) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText("Transcription", text));
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not copy the transcription", e);
            return;
        }
        // Android 13+ confirms copies on its own.
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(context, R.string.file_copied_auto, Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isPauseAudioEnabled() {
        return new java.io.File(getFilesDir(), "pause_audio").exists();
    }

    /** Opt-in via the "Auto-stop after silence" setting (default off). */
    private boolean isAutoStopEnabled() {
        return new java.io.File(getFilesDir(), "auto_stop").exists();
    }

    // Native methods
    private native void initNative(RecognizeActivity activity);
    private native void cleanupNative(RecognizeActivity self);
    private native void startRecording(boolean autoStop);
    private native void stopRecording();
    private native void cancelRecording();
}
