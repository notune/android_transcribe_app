package dev.notune.transcribe;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.content.pm.PackageManager;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;

public class RecognizeActivity extends AppCompatActivity {

    private static final String TAG = "OfflineVoiceInput";

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
    private volatile long sessionId = 0;
    private volatile boolean sessionTerminal = false;
    private boolean retryAttempted = false;
    private final DictationBuffer dictationBuffer = new DictationBuffer();
    private volatile float sentencePauseSeconds = PieceJoiner.DEFAULT_SENTENCE_PAUSE_SECONDS;

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
                cancelRecording(sessionId);
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
            status.setText("Microphone permission required.\nGrant it in the main app.");
            return;
        }

        initNative(this);
        sessionId = Math.max(1, android.os.SystemClock.elapsedRealtimeNanos());
        dictationBuffer.reset(sessionId);
        sentencePauseSeconds = readSentencePauseSeconds();
        isRecording = true;
        status.setText("Listening... (Tap to stop)");
        if (isPauseAudioEnabled()) {
            audioPauser.request(this);
            pauseAudioActive = true;
        }
        if (!startRecording(sessionId, isAutoStopEnabled())) {
            isRecording = false;
            status.setText("Could not start recording");
        }
    }

    /** Stop capture and transcribe — used by both tap-to-stop and auto-stop. */
    private void finishRecording() {
        if (!isRecording) return;
        isRecording = false;
        status.setText("Processing...");
        stopRecording(sessionId);
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
    }

    // Called from Rust (monitor thread) when trailing silence is detected.
    public void onAutoStop(long callbackSessionId) {
        if (callbackSessionId == sessionId) runOnUiThread(this::finishRecording);
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
            try { cancelRecording(sessionId); } catch (Throwable t) { /* ignore */ }
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
        try { cleanupNative(); } catch (Throwable t) { /* ignore */ }
    }

    // Called from Rust
    public void onStatusUpdate(String s) {
        runOnUiThread(() -> {
            final String shown;
            if ("Ready".equals(s)) {
                // The model-ready status can arrive after recording started.
                shown = isRecording ? "Listening... (Tap to stop)" : "Ready";
            } else if ("Listening...".equals(s)) {
                shown = "Listening... (Tap to stop)";
            } else {
                shown = s;
            }
            status.setText(shown);
        });
    }

    // Called from Rust with 0..1
    public void onAudioLevel(float level) {
        runOnUiThread(() -> micLevel.setLevel(level));
    }

    public void onDictationStatus(long callbackSessionId, String text) {
        if (callbackSessionId == sessionId) onStatusUpdate(text);
    }

    public void onDictationLevel(long callbackSessionId, float level) {
        if (callbackSessionId == sessionId) onAudioLevel(level);
    }

    public synchronized boolean onTranscriptPiece(long callbackSessionId, long pieceSequence,
                                                   String text, float pauseBeforeSeconds) {
        return !sessionTerminal && dictationBuffer.accept(callbackSessionId, pieceSequence, text,
                pauseBeforeSeconds, sentencePauseSeconds);
    }

    public void onDictationComplete(long callbackSessionId, int outcome,
                                    String text, String error) {
        runOnUiThread(() -> {
            if (callbackSessionId != sessionId || sessionTerminal) return;
            if (outcome == 1 && !retryAttempted) {
                retryAttempted = true;
                if (retryRecording(sessionId)) {
                    status.setText("Retrying...");
                    return;
                }
            }
            sessionTerminal = true;
            if (outcome != 0) {
                dictationBuffer.discard();
                if (outcome == 1) cancelRecording(sessionId);
                if (outcome != 2) status.setText(error == null || error.isEmpty()
                        ? "Dictation could not be completed" : "Error: " + error);
                setResult(Activity.RESULT_CANCELED);
                finish();
                return;
            }
            String completed = dictationBuffer.finish(callbackSessionId);
            if (!completed.isEmpty()) onTextTranscribed(completed);
            else {
                status.setText(error == null || error.isEmpty()
                        ? "Nothing was recognized" : "Error: " + error);
                setResult(Activity.RESULT_CANCELED);
                finish();
            }
        });
    }

    // Called from Rust – keep same method name as IME for code reuse
    public void onTextTranscribed(String text) {
        runOnUiThread(() -> {
            if (text == null || text.trim().isEmpty()) {
                // Nothing was recognized (e.g. auto-stop after silence only).
                setResult(Activity.RESULT_CANCELED);
                finish();
                return;
            }

            ArrayList<String> results = new ArrayList<>();
            results.add(text);

            Intent data = new Intent();
            data.putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, results);

            setResult(Activity.RESULT_OK, data);
            finish();
        });
    }

    private boolean isPauseAudioEnabled() {
        return new java.io.File(getFilesDir(), "pause_audio").exists();
    }

    /** Opt-in via the "Auto-stop after silence" setting (default off). */
    private boolean isAutoStopEnabled() {
        return new java.io.File(getFilesDir(), "auto_stop").exists();
    }

    private float readSentencePauseSeconds() {
        java.io.File file = new java.io.File(getFilesDir(), "pause_sentence_seconds");
        if (!file.exists()) return PieceJoiner.DEFAULT_SENTENCE_PAUSE_SECONDS;
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.FileReader(file))) {
            return SentencePauseSetting.parse(reader.readLine());
        } catch (Throwable t) {
            return PieceJoiner.DEFAULT_SENTENCE_PAUSE_SECONDS;
        }
    }

    // Native methods
    private native void initNative(RecognizeActivity activity);
    private native void cleanupNative();
    private native boolean startRecording(long sessionId, boolean autoStop);
    private native boolean stopRecording(long sessionId);
    private native boolean cancelRecording(long sessionId);
    private native boolean retryRecording(long sessionId);
}
