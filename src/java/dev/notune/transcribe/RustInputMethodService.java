package dev.notune.transcribe;

import android.inputmethodservice.InputMethodService;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ProgressBar;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.content.Context;

public class RustInputMethodService extends InputMethodService {
    
    private static final String TAG = "OfflineVoiceInput";

    static {
        try {
            System.loadLibrary("android_transcribe_app");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native library", e);
        }
    }

    private TextView statusView;
    private Button recordButton;
    private ProgressBar progressBar;
    private Handler mainHandler;
    private boolean isRecording = false;

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        Log.d(TAG, "Service onCreate");
        try {
            initNative(this);
        } catch (Exception e) {
            Log.e(TAG, "Error in initNative", e);
        }
    }

    @Override
    public View onCreateInputView() {
        Log.d(TAG, "onCreateInputView");
        try {
            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            // Explicit height to prevent collapse
            layout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 
                600
            ));
            layout.setPadding(30, 30, 30, 30);
            layout.setBackgroundColor(0xFFF5F5F5); 
            
            // Status Row
            statusView = new TextView(this);
            statusView.setText("Initializing...");
            statusView.setTextSize(16);
            statusView.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            statusView.setPadding(0, 0, 0, 20);
            statusView.setTextColor(0xFF333333);
            
            // Progress Bar (Visible by default until ready)
            progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            progressBar.setIndeterminate(true);
            progressBar.setVisibility(View.VISIBLE);
            
            // Record Button
            recordButton = new Button(this);
            recordButton.setText("🎤 Tap to Speak");
            recordButton.setBackgroundColor(0xFF2196F3); // Blue
            recordButton.setTextColor(0xFFFFFFFF);
            recordButton.setTextSize(18);
            recordButton.setPadding(20, 20, 20, 20);
            // Disable until ready
            recordButton.setEnabled(false);
            recordButton.setAlpha(0.5f);
            
            recordButton.setOnClickListener(v -> {
                if (isRecording) {
                    stopRecording();
                    updateRecordButtonUI(false);
                } else {
                    startRecording();
                    updateRecordButtonUI(true);
                }
            });

            layout.addView(statusView);
            layout.addView(progressBar);
            layout.addView(recordButton);

            // Spacer to avoid overlap with system navigation/keyboard switcher
            View spacer = new View(this);
            spacer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 
                150
            ));
            layout.addView(spacer);
            
            return layout;
        } catch (Exception e) {
            Log.e(TAG, "Error in onCreateInputView", e);
            TextView errorView = new TextView(this);
            errorView.setText("Error loading keyboard: " + e.getMessage());
            return errorView;
        }
    }
    
    private void updateRecordButtonUI(boolean recording) {
        isRecording = recording;
        if (recording) {
            recordButton.setText("Stop");
            recordButton.setBackgroundColor(0xFFF44336); // Red
            statusView.setText("Listening...");
        } else {
            recordButton.setText("🎤 Tap to Speak");
            recordButton.setBackgroundColor(0xFF2196F3); // Blue
            statusView.setText("Processing...");
        }
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        cleanupNative();
    }

    // Native methods
    private native void initNative(RustInputMethodService service);
    private native void cleanupNative();
    private native void startRecording();
    private native void stopRecording();
    
    // Called from Rust
    public void onStatusUpdate(String status) {
        mainHandler.post(() -> {
            Log.d(TAG, "Status: " + status);
            if (statusView != null) statusView.setText(status);
            
            // Logic to unlock UI when ready
            if (status.contains("Ready") || status.contains("Listening")) {
                if (progressBar != null) progressBar.setVisibility(View.GONE);
                if (recordButton != null) {
                    recordButton.setEnabled(true);
                    recordButton.setAlpha(1.0f);
                }
            } else if (status.contains("Initializing") || status.contains("Loading")) {
                if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
                if (recordButton != null) {
                    recordButton.setEnabled(false);
                    recordButton.setAlpha(0.5f);
                }
            }
        });
    }
    
    // Called from Rust
    public void onTextTranscribed(String text) {
        mainHandler.post(() -> {
            if (getCurrentInputConnection() != null) {
                getCurrentInputConnection().commitText(text + " ", 1);
            }
            updateRecordButtonUI(false);
            if (statusView != null) statusView.setText("Ready");
        });
    }
}