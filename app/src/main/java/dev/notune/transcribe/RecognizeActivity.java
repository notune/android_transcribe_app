package dev.notune.transcribe;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.content.pm.PackageManager;

import java.util.ArrayList;

public class RecognizeActivity extends Activity {

    private static final String TAG = "OfflineVoiceInput";

    static {
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("onnxruntime");
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.recognize_activity);

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
        findViewById(R.id.root).setOnClickListener(v -> {
            if (isRecording) {
                isRecording = false;
                status.setText("Processing...");
                stopRecording();
                if (pauseAudioActive) {
                    audioPauser.abandon(this);
                    pauseAudioActive = false;
                }
            }
        });

        // Permission check
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            status.setText("Microphone permission required.\nGrant it in the main app.");
            return;
        }

        initNative(this);
        isRecording = true;
        status.setText("Listening... (Tap to stop)");
        if (isPauseAudioEnabled()) {
            audioPauser.request(this);
            pauseAudioActive = true;
        }
        startRecording();
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
        final String shown;
        if ("Ready".equals(s)) {
            shown = "Ready (Tap to stop)";
        } else if ("Listening...".equals(s)) {
            shown = "Listening... (Tap to stop)";
        } else {
            shown = s;
        }

        runOnUiThread(() -> status.setText(shown));
    }

    // Called from Rust with 0..1
    public void onAudioLevel(float level) {
        runOnUiThread(() -> micLevel.setLevel(level));
    }

    // Called from Rust – keep same method name as IME for code reuse
    public void onTextTranscribed(String text) {
        runOnUiThread(() -> {
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

    // Native methods
    private native void initNative(RecognizeActivity activity);
    private native void cleanupNative();
    private native void startRecording();
    private native void stopRecording();
    private native void cancelRecording();
}
