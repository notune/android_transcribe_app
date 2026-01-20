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
import android.view.MotionEvent;

public class RustInputMethodService extends InputMethodService {
    
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

    private TextView statusView;
    private TextView hintView;
    private View recordContainer;
    private android.widget.ImageView micIcon;
    private ProgressBar progressBar;
    private View backspaceButton;
    private View spaceButton;
    private View enterButton;
    private Handler mainHandler;
    private boolean isRecording = false;
    private String lastStatus = "Initializing...";

    // Key repeat settings
    private static final long REPEAT_INITIAL_DELAY = 400; // ms before repeat starts
    private static final long REPEAT_INTERVAL = 50; // ms between repeats
    private Runnable backspaceRepeatRunnable;
    private Runnable spaceRepeatRunnable;

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
            View view = getLayoutInflater().inflate(R.layout.ime_layout, null);
            
            statusView = view.findViewById(R.id.ime_status_text);
            progressBar = view.findViewById(R.id.ime_progress);
            recordContainer = view.findViewById(R.id.ime_record_container);
            micIcon = view.findViewById(R.id.ime_mic_icon);
            hintView = view.findViewById(R.id.ime_hint);
            backspaceButton = view.findViewById(R.id.ime_backspace);
            spaceButton = view.findViewById(R.id.ime_space);
            enterButton = view.findViewById(R.id.ime_enter);

            // Key repeat runnable for backspace
            backspaceRepeatRunnable = new Runnable() {
                @Override
                public void run() {
                    if (getCurrentInputConnection() != null) {
                        getCurrentInputConnection().deleteSurroundingText(1, 0);
                    }
                    mainHandler.postDelayed(this, REPEAT_INTERVAL);
                }
            };

            // Key repeat runnable for space
            spaceRepeatRunnable = new Runnable() {
                @Override
                public void run() {
                    if (getCurrentInputConnection() != null) {
                        getCurrentInputConnection().commitText(" ", 1);
                    }
                    mainHandler.postDelayed(this, REPEAT_INTERVAL);
                }
            };

            backspaceButton.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        // Delete one character immediately
                        if (getCurrentInputConnection() != null) {
                            getCurrentInputConnection().deleteSurroundingText(1, 0);
                        }
                        // Schedule repeat after initial delay
                        mainHandler.postDelayed(backspaceRepeatRunnable, REPEAT_INITIAL_DELAY);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        // Stop repeating
                        mainHandler.removeCallbacks(backspaceRepeatRunnable);
                        return true;
                }
                return false;
            });

            spaceButton.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        // Insert space immediately
                        if (getCurrentInputConnection() != null) {
                            getCurrentInputConnection().commitText(" ", 1);
                        }
                        // Schedule repeat after initial delay
                        mainHandler.postDelayed(spaceRepeatRunnable, REPEAT_INITIAL_DELAY);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        // Stop repeating
                        mainHandler.removeCallbacks(spaceRepeatRunnable);
                        return true;
                }
                return false;
            });

            enterButton.setOnClickListener(v -> {
                if (getCurrentInputConnection() != null) {
                    getCurrentInputConnection().commitText("\n", 1);
                }
            });

            recordContainer.setOnClickListener(v -> {
                if (!recordContainer.isEnabled()) return;
                
                if (isRecording) {
                    stopRecording();
                    updateRecordButtonUI(false);
                } else {
                    startRecording();
                    updateRecordButtonUI(true);
                }
            });

            updateUiState();
            return view;
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
            micIcon.setColorFilter(0xFFF44336); // Red
            statusView.setText("Listening...");
            hintView.setText("Tap to Stop");
        } else {
            micIcon.setColorFilter(0xFF2196F3); // Blue
            statusView.setText("Processing...");
            hintView.setText("Tap to Record");
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
            lastStatus = status;
            updateUiState();
        });
    }

    private void updateUiState() {
        if (statusView != null) statusView.setText(lastStatus);

        if (lastStatus.contains("Ready") || lastStatus.contains("Listening")) {
            if (progressBar != null) progressBar.setVisibility(View.GONE);
            if (recordContainer != null) {
                recordContainer.setEnabled(true);
                recordContainer.setAlpha(1.0f);
            }
        } else if (lastStatus.contains("Initializing") || lastStatus.contains("Loading")) {
            if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
            if (recordContainer != null) {
                recordContainer.setEnabled(false);
                recordContainer.setAlpha(0.5f);
            }
        }
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