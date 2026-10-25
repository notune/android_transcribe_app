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
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.content.pm.PackageManager;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;

public class RecognizeActivity extends AppCompatActivity {

    private static final String TAG = "OfflineVoiceInput";
    /** How long a keyboard gets to insert the result before it is copied. */
    private static final long CLIPBOARD_DELAY_MS = 1000;
    /** Live text longer than this shows only its end, so the panel stays compact. */
    private static final int LIVE_TEXT_MAX_CHARS = 160;

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
    private MicLevelView micLevel;
    private final AudioFocusPauser audioPauser = new AudioFocusPauser();
    private boolean pauseAudioActive = false;
    /** A streaming model is showing its text in the panel (replacing the status). */
    private boolean showingLiveText = false;

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
            // discard current recording
            if (isRecording) {
                isRecording = false;
                cancelRecording();   // new native method
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
        status.setText(R.string.voice_listening);
        if (isPauseAudioEnabled()) {
            audioPauser.request(this);
            pauseAudioActive = true;
        }
        startRecording(isAutoStopEnabled(), !new java.io.File(getFilesDir(), "popup_batch").exists());
    }

    /** Stop capture and transcribe — used by both tap-to-stop and auto-stop. */
    private void finishRecording() {
        if (!isRecording) return;
        isRecording = false;
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
        if (isRecording && !isFinishing()) {
            isRecording = false;
            try { cancelRecording(); } catch (Throwable t) { /* ignore */ }
            if (pauseAudioActive) {
                audioPauser.abandon(this);
                pauseAudioActive = false;
            }
            setResult(Activity.RESULT_CANCELED);
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
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
            } else if (showingLiveText) {
                // Keep showing the text; busy states don't need words here.
            } else {
                status.setText(isRecording ? R.string.voice_listening : R.string.voice_processing);
            }
        });
    }

    // Called from Rust while a streaming model transcribes (see live.rs):
    // committed text only grows, the tentative rest may still change.
    public void onStreamText(String committed, String tentative) {
        runOnUiThread(() -> {
            if (isFinishing()) return;
            showingLiveText = true;
            String text = committed + tentative;
            int tentativeStart = committed.length();
            if (text.length() > LIVE_TEXT_MAX_CHARS) {
                int cut = text.length() - LIVE_TEXT_MAX_CHARS;
                int space = text.indexOf(' ', cut);
                if (space >= 0 && space < text.length() - 1) cut = space + 1;
                text = "…" + text.substring(cut);
                tentativeStart = Math.max(1, tentativeStart - cut + 1);
            }
            SpannableString shown = new SpannableString(text);
            if (tentativeStart < text.length()) {
                shown.setSpan(new ForegroundColorSpan(MaterialColors.getColor(status,
                                com.google.android.material.R.attr.colorOnSurfaceVariant)),
                        tentativeStart, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            status.setText(shown);
        });
    }

    // Called from Rust with 0..1
    public void onAudioLevel(float level) {
        runOnUiThread(() -> micLevel.setLevel(level));
    }

    // Called from Rust – keep same method name as IME for code reuse
    public void onTextTranscribed(String text) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (text == null || text.trim().isEmpty()) {
                // Nothing was recognized (e.g. auto-stop after silence only).
                // Say so: otherwise the panel just vanishes as if it broke.
                Toast.makeText(getApplicationContext(), R.string.voice_no_speech,
                        Toast.LENGTH_SHORT).show();
                setResult(Activity.RESULT_CANCELED);
                finish();
                return;
            }

            deliverResult(text.trim());
        });
    }

    private void deliverResult(String text) {
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
    private native void startRecording(boolean autoStop, boolean liveEnabled);
    private native void stopRecording();
    private native void cancelRecording();
}
