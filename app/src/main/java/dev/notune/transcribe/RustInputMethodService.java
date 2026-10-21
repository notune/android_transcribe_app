package dev.notune.transcribe;

import android.inputmethodservice.InputMethodService;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ProgressBar;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.content.Context;
import android.content.pm.PackageManager;
import android.view.MotionEvent;
import android.view.inputmethod.EditorInfo;
import android.content.res.ColorStateList;
import android.view.ContextThemeWrapper;
import java.io.File;

import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;

public class RustInputMethodService extends InputMethodService {
    
    private static final String TAG = "OfflineVoiceInput";

    static {
        try {
            System.loadLibrary("c++_shared");
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
    private View switchKeyboardButton;
    private View inputView;
    private MicLevelView micLevelView;
    private View recordCircle;
    // Night flag the current input view was inflated with, so it can be rebuilt
    // if the theme preference changes while this process stays alive.
    private boolean viewIsNight = false;
    private Handler mainHandler;
    private boolean isRecording = false;
    private boolean pendingSwitchBack = false;
    private String lastStatus = "Initializing...";
    // Key repeat settings
    private static final long REPEAT_INITIAL_DELAY = 400; // ms before repeat starts
    private static final long REPEAT_INTERVAL = 50; // ms between repeats
    private Runnable backspaceRepeatRunnable;
    private Runnable spaceRepeatRunnable;
    private final AudioFocusPauser audioPauser = new AudioFocusPauser();
    private boolean pauseAudioActive = false;
    // Whether an editor is currently focused/started for input. Tracked via
    // onStartInput/onFinishInput because getCurrentInputConnection() returns a
    // non-null no-op connection when nothing is focused, so commitText would be
    // silently dropped.
    private boolean inputActive = false;
    // Whether the keyboard window is currently on screen. Some frameworks
    // (notably OEM builds) call onWindowShown again for events that don't
    // follow an onWindowHidden, e.g. tapping the text area to move the
    // cursor while the keyboard stays visible. Auto-record must only fire on
    // a genuine hidden -> shown transition, or a cursor tap starts a
    // recording the user never asked for.
    private boolean windowVisible = false;
    // Transcribed text waiting to be committed because no editor was focused
    // when transcription finished. This happens on long transcribes where the
    // target field (e.g. a web field in Firefox/Gemini) drops focus while we
    // process audio. Flushed from onStartInputView once a field is focused
    // again so the text is never lost.
    private String pendingCommitText = null;

    // One composing region covers the dictation. Updates replace it; only
    // final delivery commits it, preserving editor Undo and selected text.
    private boolean streamActive, streamComposing, streamDiscarded;
    private boolean streamCompositionSeen, streamDeferred, captureFinishing;
    private String streamCommitted = "", streamTentative = "", streamLead;
    private final StringBuilder streamTypedHere = new StringBuilder();
    private String streamOriginalSelection = "";
    private int streamStart = -1, streamOriginalEnd = -1;
    private long recordingGeneration;
    // Snapshot per recording: a settings change applies on the next start.
    // This controls field insertion only, never native streaming inference.
    private boolean continuousTypingThisRecording;

    private final class RecordingCallbacks extends android.content.ContextWrapper {
        private final long generation;
        RecordingCallbacks(long generation) {
            super(RustInputMethodService.this);
            this.generation = generation;
        }
        public void onStatusUpdate(String s) { handleStatus(generation, s); }
        public void onTextTranscribed(String s) { handleText(generation, s); }
        public void onStreamText(String c, String t) { handleStream(generation, c, t); }
        public void onAudioLevel(float level) { handleLevel(generation, level); }
        public void onAutoStop() {
            mainHandler.post(() -> {
                if (generation == recordingGeneration && isRecording) finishCapture();
            });
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        Log.d(TAG, "Service onCreate");
        try {
            initNative(this);
        } catch (Throwable t) {
            // Native may be unavailable (e.g. wrong-ABI emulator); don't crash the IME.
            Log.e(TAG, "Error in initNative", t);
        }
    }

    @Override
    public View onCreateInputView() {
        Log.d(TAG, "onCreateInputView");
        try {
            // The IME is a non-AppCompat Service, so AppCompat's delegate can't
            // theme it. Build a context that is night-aware (per the saved
            // preference), wears the Material 3 theme, and picks up Material
            // You dynamic color — matching the app.
            Context night = ThemePrefs.wrapForNight(this, ThemePrefs.getMode(this));
            viewIsNight = ThemePrefs.isNight(night);
            Context themed = DynamicColors.wrapContextIfAvailable(
                    new ContextThemeWrapper(night, R.style.AppTheme));
            View view = LayoutInflater.from(themed).inflate(R.layout.ime_layout, null);
            inputView = view;

            // Keep the keys above the navigation bar: pad the bottom by its
            // height. When the keyboard starts cold, Android 16 can size the
            // window from a layout pass that ran before these insets arrived and
            // keep that size, cutting off the bottom row; lay out again once the
            // padding changes so the window grows to fit it.
            view.setOnApplyWindowInsetsListener((v, insets) -> {
                int paddingBottom = v.getPaddingTop() + insets.getSystemWindowInsetBottom();
                if (v.getPaddingBottom() != paddingBottom) {
                    v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), paddingBottom);
                    v.post(v::requestLayout);
                }
                return insets;
            });

            statusView = view.findViewById(R.id.ime_status_text);
            progressBar = view.findViewById(R.id.ime_progress);
            recordContainer = view.findViewById(R.id.ime_record_container);
            micIcon = view.findViewById(R.id.ime_mic_icon);
            micLevelView = view.findViewById(R.id.ime_mic_level);
            recordCircle = view.findViewById(R.id.ime_record_circle);
            hintView = view.findViewById(R.id.ime_hint);
            backspaceButton = view.findViewById(R.id.ime_backspace);
            spaceButton = view.findViewById(R.id.ime_space);
            enterButton = view.findViewById(R.id.ime_enter);
            switchKeyboardButton = view.findViewById(R.id.ime_switch_keyboard);

            switchKeyboardButton.setOnClickListener(v -> {
                if (isRecording) {
                    pendingSwitchBack = true;
                    finishCapture();
                } else {
                    switchAwayFromVoiceKeyboard();
                }
            });

            // Key repeat runnable for backspace
            backspaceRepeatRunnable = new Runnable() {
                @Override
                public void run() {
                    deleteBackward();
                    mainHandler.postDelayed(this, REPEAT_INTERVAL);
                }
            };

            // Key repeat runnable for space
            spaceRepeatRunnable = new Runnable() {
                @Override
                public void run() {
                    insertSpace();
                    mainHandler.postDelayed(this, REPEAT_INTERVAL);
                }
            };

            // Touch drives the key repeat; the click listeners serve
            // activation without touch (TalkBack, Switch Access, Voice Access),
            // which calls performClick and never sends touch events.
            backspaceButton.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        deleteBackward();
                        mainHandler.postDelayed(backspaceRepeatRunnable, REPEAT_INITIAL_DELAY);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        mainHandler.removeCallbacks(backspaceRepeatRunnable);
                        return true;
                }
                return false;
            });
            backspaceButton.setOnClickListener(v -> deleteBackward());

            spaceButton.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        insertSpace();
                        mainHandler.postDelayed(spaceRepeatRunnable, REPEAT_INITIAL_DELAY);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        mainHandler.removeCallbacks(spaceRepeatRunnable);
                        return true;
                }
                return false;
            });
            spaceButton.setOnClickListener(v -> insertSpace());

            enterButton.setOnClickListener(v -> {
                stopForEditorChange();
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) {
                    dropTentative(ic);
                    android.view.inputmethod.EditorInfo editorInfo = getCurrentInputEditorInfo();
                    if (editorInfo == null) {
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER));
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER));
                        return;
                    }
                    int imeOptions = editorInfo.imeOptions;
                    int action = imeOptions & android.view.inputmethod.EditorInfo.IME_MASK_ACTION;
                    boolean noEnterAction = (imeOptions & android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;

                    // If the editor flags IME_FLAG_NO_ENTER_ACTION (e.g. multi-line fields in
                    // messaging apps like Signal), or if there's no meaningful action, insert a
                    // newline. Otherwise perform the editor action (Go, Search, Send, etc.).
                    if (!noEnterAction && (
                            action == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                            action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                            action == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                            action == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT)) {
                        ic.performEditorAction(action);
                    } else {
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER));
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER));
                    }
                }
            });

            recordContainer.setOnClickListener(v -> {
                if (!recordContainer.isEnabled()) return;

                // Check microphone permission
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    if (statusView != null) statusView.setText(R.string.ime_need_mic);
                    if (hintView != null) hintView.setText(R.string.ime_need_mic_hint);
                    return;
                }

                if (!isRecording && !ModelStore.hasAnyModel(this)) {
                    updateUiState();
                    return;
                }

                if (isRecording) {
                    finishCapture();
                } else {
                    if (isPauseAudioEnabled()) {
                        audioPauser.request(this);
                        pauseAudioActive = true;
                    }
                    beginRecording();
                    updateRecordButtonUI(true);
                }
            });

            tintRecordButton(false);
            updateUiState();
            return view;
        } catch (Exception e) {
            Log.e(TAG, "Error in onCreateInputView", e);
            TextView errorView = new TextView(this);
            errorView.setText("Error loading keyboard: " + e.getMessage());
            return errorView;
        }
    }

    private void deleteBackward() {
        stopForEditorChange();
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            dropTentative(ic);
            ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL));
            ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DEL));
        }
    }

    private void insertSpace() {
        stopForEditorChange();
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            dropTentative(ic);
            ic.commitText(" ", 1);
        }
    }

    /** Starts a recording, with no live text from an earlier one. */
    private void beginRecording() {
        if (captureFinishing) return;
        resetStream();
        streamDiscarded = false;
        continuousTypingThisRecording = new File(getFilesDir(), "continuous_typing").exists();
        startRecording(new RecordingCallbacks(++recordingGeneration));
    }

    /**
     * Stops capture and hands the audio to transcription. Audio focus is
     * released right away, so paused media can resume while the (possibly
     * long) transcription runs.
     */
    private void finishCapture() {
        captureFinishing = true;
        lastStatus = "Transcribing...";
        stopRecording();
        releaseAudioFocus();
        updateRecordButtonUI(false);
    }

    private void releaseAudioFocus() {
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
    }

    /**
     * Leaves the voice keyboard. There is no previous keyboard when ours was
     * picked in the system settings (common on Android TV) or after the input
     * method history was reset; then fall back to the next enabled keyboard,
     * and finally to the system picker, so the button never does nothing.
     */
    private void switchAwayFromVoiceKeyboard() {
        if (switchToLastKeyboard() || switchToNextKeyboard()) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showInputMethodPicker();
    }

    // InputMethodService#switchToPreviousInputMethod / #switchToNextInputMethod
    // only exist since API 28; older versions take the window token.
    @SuppressWarnings("deprecation")
    private boolean switchToLastKeyboard() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return switchToPreviousInputMethod();
            }
            IBinder token = imeWindowToken();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            return token != null && imm != null && imm.switchToLastInputMethod(token);
        } catch (RuntimeException e) {
            Log.w(TAG, "switching to the previous keyboard failed", e);
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private boolean switchToNextKeyboard() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return switchToNextInputMethod(false);
            }
            IBinder token = imeWindowToken();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            return token != null && imm != null && imm.switchToNextInputMethod(token, false);
        } catch (RuntimeException e) {
            Log.w(TAG, "switching to the next keyboard failed", e);
            return false;
        }
    }

    private IBinder imeWindowToken() {
        android.app.Dialog window = getWindow();
        if (window == null || window.getWindow() == null) return null;
        return window.getWindow().getAttributes().token;
    }

    @Override
    public void onWindowShown() {
        super.onWindowShown();
        boolean wasVisible = windowVisible;
        windowVisible = true;
        if (isRecording) {
            // A background recording is still running (record-in-background
            // setting): restore the recording UI.
            updateRecordButtonUI(true);
            return;
        }
        if (wasVisible) {
            // Not a real hidden -> shown transition (e.g. a cursor tap in the
            // text area); never auto-start a recording from here.
            return;
        }
        if (!captureFinishing && new File(getFilesDir(), "auto_record").exists()) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                if (isPauseAudioEnabled()) {
                    audioPauser.request(this);
                    pauseAudioActive = true;
                }
                beginRecording();
                updateRecordButtonUI(true);
            }
        }
    }

    @Override
    public void onWindowHidden() {
        super.onWindowHidden();
        windowVisible = false;
        if (isRecording) {
            if (isStopOnHideEnabled()) {
                // Opt-in behavior: discard the recording when the keyboard hides.
                discardStreamText();
                try {
                    cancelRecording();
                } catch (Throwable t) {
                    Log.w(TAG, "cancelRecording failed, falling back to stopRecording", t);
                    try { stopRecording(); } catch (Throwable ignored) { }
                }
                updateRecordButtonUI(false);
            } else {
                // Default: keep recording in the background. The transcription
                // is committed on return (or held in pendingCommitText).
                return;
            }
        }
        releaseAudioFocus();
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        inputActive = isTextEditor(attribute);
    }

    /**
     * Since Android 11 the keyboard also gets onStartInput for windows without
     * any text field (e.g. the launcher after pressing Home, or a browser
     * between page focus changes), with a placeholder EditorInfo. Text
     * committed there is silently lost, so it doesn't count as an editor.
     * Terminal-style views that really use TYPE_NULL still have a view id.
     */
    private static boolean isTextEditor(EditorInfo info) {
        return info != null && !(info.inputType == android.text.InputType.TYPE_NULL
                && info.initialSelStart < 0 && info.fieldId == 0);
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        inputActive = true;
        // Rebuild the keyboard if the theme preference changed while this
        // (long-lived) keyboard service stayed alive, so it matches the app
        // setting.
        if (inputView != null
                && ThemePrefs.isNight(ThemePrefs.wrapForNight(this, ThemePrefs.getMode(this))) != viewIsNight) {
            View rebuilt = onCreateInputView();
            setInputView(rebuilt);
            // The window already dispatched its insets to the old view; ask
            // for a new pass so the replacement gets its navigation-bar
            // padding (otherwise the bottom key row is cut off).
            rebuilt.requestApplyInsets();
        }
        // A field is focused and the input connection is live again — commit any
        // text that finished transcribing while nothing was focused.
        flushPendingText();
        if (streamActive) applyStreamText();
    }

    @Override
    public void onFinishInput() {
        if (streamActive) {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) dropTentative(ic);
            clearStreamRegion();
        }
        super.onFinishInput();
        inputActive = false;
    }

    private void updateRecordButtonUI(boolean recording) {
        isRecording = recording;
        // Keep the screen awake while recording so it never sleeps mid-capture
        // and cuts the recording short. Cleared automatically once we stop.
        if (inputView != null) {
            inputView.setKeepScreenOn(recording);
        }
        tintRecordButton(recording);
        if (recording) {
            statusView.setText(R.string.ime_listening);
            hintView.setText(R.string.ime_tap_to_stop);
        } else {
            statusView.setText(R.string.voice_processing);
            hintView.setText(R.string.ime_tap_to_record);
            if (micLevelView != null) micLevelView.setLevel(0f);
        }
        // Ready is posted before the final text callback. At that point
        // captureFinishing is still true, so handleStatus keeps Record
        // disabled. Refresh again after the result clears that flag.
        updateUiState();
    }

    /** Tints the round record button + mic: idle = primary, recording = error. */
    private void tintRecordButton(boolean recording) {
        int circleAttr = recording
                ? com.google.android.material.R.attr.colorPrimary
                : com.google.android.material.R.attr.colorPrimaryContainer;
        int iconAttr = recording
                ? com.google.android.material.R.attr.colorOnPrimary
                : com.google.android.material.R.attr.colorOnPrimaryContainer;
        if (recordCircle != null) {
            recordCircle.setBackgroundTintList(ColorStateList.valueOf(
                    MaterialColors.getColor(recordCircle, circleAttr)));
        }
        if (micIcon != null) {
            micIcon.setColorFilter(MaterialColors.getColor(micIcon, iconAttr));
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        cleanupNative(this);
        releaseAudioFocus();
    }

    // Native methods
    private native void initNative(RustInputMethodService service);
    private native void cleanupNative(RustInputMethodService self);
    private native void startRecording(RecordingCallbacks callbacks);
    private native void stopRecording();
    private native void cancelRecording();

    // Called from Rust
    public void onStatusUpdate(String status) {
        handleStatus(recordingGeneration, status);
    }

    private void handleStatus(long generation, String status) {
        mainHandler.post(() -> {
            if (generation != recordingGeneration) return;
            if (status != null && status.startsWith("Error")) {
                captureFinishing = false;
                if (isRecording) {
                    ++recordingGeneration;
                    cancelRecording();
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null) ic.finishComposingText();
                    resetStream();
                    updateRecordButtonUI(false);
                }
            }
            Log.d(TAG, "Status: " + status);
            lastStatus = status;
            updateUiState();
            if (pendingSwitchBack && status.startsWith("Error")) {
                pendingSwitchBack = false;
                switchAwayFromVoiceKeyboard();
            }
            if (status != null && status.startsWith("Error")) {
                releaseAudioFocus();
                if (streamActive && !isRecording) {
                    // The stream ended without a result: keep the text that
                    // was committed, drop the guess after it.
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null) dropTentative(ic);
                    resetStream();
                }
            }
        });
    }

    private void updateUiState() {
        boolean isLoading = lastStatus.contains("Loading") || lastStatus.contains("Initializing");
        boolean isWaiting = lastStatus.contains("Waiting");
        boolean isTranscribing = lastStatus.contains("Transcribing") || lastStatus.contains("Processing");
        boolean isError = lastStatus.startsWith("Error");
        boolean isReady = lastStatus.equals("Ready");
        boolean noModel = !isRecording && !ModelStore.hasAnyModel(this);

        // Don't show internal loading states to the user
        if (statusView != null && !isRecording) {
            if (isError && !lastStatus.equals(ModelStore.NO_MODEL_STATUS)) {
                statusView.setText(lastStatus);
            } else if (isTranscribing || isWaiting) {
                statusView.setText(R.string.voice_processing);
            } else if (noModel) {
                statusView.setText(R.string.no_model_title);
            } else {
                statusView.setText(R.string.ime_tap_to_record);
            }
        }

        // Hide progress bar - don't expose model loading to user
        if (progressBar != null) {
            progressBar.setVisibility(View.GONE);
        }

        // Disable the button only while busy. After an error it stays usable:
        // most errors are transient (no audio, storage was full while
        // extracting the model, mic busy), and the next attempt retries.
        if (recordContainer != null) {
            boolean disable = isTranscribing || isWaiting || captureFinishing;
            recordContainer.setEnabled(!disable);
            recordContainer.setAlpha(disable ? 0.5f : 1.0f);
        }

        if (hintView != null && !isRecording) {
            hintView.setText(noModel ? R.string.ime_no_model_hint : R.string.ime_tap_to_record);
        }
    }

    // Called from Rust
    public void onTextTranscribed(String text) {
        handleText(recordingGeneration, text);
    }

    private void handleText(long generation, String text) {
        mainHandler.post(() -> {
            if (generation != recordingGeneration || streamDiscarded) return;
            captureFinishing = false;
            if (streamActive) {
                finishStream(text == null ? "" : text);
                return;
            }
            if (text == null || text.trim().isEmpty()) {
                // Nothing recognized — don't insert a stray space.
                updateRecordButtonUI(false);
                if (statusView != null) statusView.setText(R.string.voice_no_speech);
                releaseAudioFocus();
                if (pendingSwitchBack) {
                    pendingSwitchBack = false;
                    switchAwayFromVoiceKeyboard();
                }
                return;
            }
            String committed = text + " ";
            InputConnection ic = getCurrentInputConnection();
            if (inputActive && ic != null) {
                commitTranscribedText(ic, committed);
            } else {
                // No editor is focused right now (common on long transcribes where
                // a web field in Firefox/Gemini dropped focus while we processed
                // audio). Committing now would be silently dropped, so defer the
                // text until a field is focused again instead of losing it.
                pendingCommitText = committed;
            }
            releaseAudioFocus();
            updateRecordButtonUI(false);
            if (statusView != null) statusView.setText(R.string.ime_tap_to_record);
            if (pendingSwitchBack) {
                pendingSwitchBack = false;
                switchAwayFromVoiceKeyboard();
            }
        });
    }

    // Commits transcribed text into the active input connection, optionally
    // selecting it afterwards (select_transcription setting).
    private void commitTranscribedText(InputConnection ic, String committed) {
        // Keep the dictation apart from a word right before the cursor
        // ("hello wor" + "Weather" must not become "hello worWeather").
        CharSequence before = ic.getTextBeforeCursor(1, 0);
        if (before != null && before.length() > 0
                && !Character.isWhitespace(before.charAt(before.length() - 1))) {
            committed = " " + committed;
        }
        ic.commitText(committed, 1);

        if (!pendingSwitchBack && new File(getFilesDir(), "select_transcription").exists()) {
            selectBeforeCursor(ic, committed.length());
        }
    }

    // Selects the `length` characters before the cursor.
    private void selectBeforeCursor(InputConnection ic, int length) {
        android.view.inputmethod.ExtractedText et = ic.getExtractedText(
            new android.view.inputmethod.ExtractedTextRequest(), 0);
        if (et != null) {
            int end = et.startOffset + et.selectionStart;
            int start = end - length;
            if (start >= 0) {
                ic.setSelection(start, end);
            }
        }
    }

    // Commits text that finished transcribing while no field was focused. Called
    // from onStartInputView when an editor (and a live input connection) is
    // available again.
    private void flushPendingText() {
        if (pendingCommitText == null) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            commitTranscribedText(ic, pendingCommitText);
            pendingCommitText = null;
        }
    }
    public void onStreamText(String committed, String tentative) {
        handleStream(recordingGeneration, committed, tentative);
    }

    private void handleStream(long generation, String committed, String tentative) {
        mainHandler.post(() -> {
            if (generation != recordingGeneration || streamDiscarded
                    || !continuousTypingThisRecording) return;
            streamActive = true;
            streamCommitted = committed;
            streamTentative = tentative;
            applyStreamText();
        });
    }

    /** Replace a single verified region, never append revised words. */
    private void applyStreamText() {
        InputConnection ic = getCurrentInputConnection();
        if (!inputActive || ic == null) return;
        if (streamDeferred) {
            if (hintView != null) hintView.setText(streamCommitted + streamTentative);
            return;
        }
        if (!prepareStreamRegion(ic)) return;
        if (streamComposing && !ownsStreamRegion(ic)) {
            stopForEditorChange();
            return;
        }
        String text = streamLead + streamCommitted + streamTentative;
        android.text.SpannableString shown = new android.text.SpannableString(text);
        int tentativeStart = streamLead.length() + streamCommitted.length();
        if (tentativeStart < text.length()) {
            shown.setSpan(new android.text.style.UnderlineSpan(), tentativeStart, text.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    | android.text.Spanned.SPAN_COMPOSING);
        }
        // A Spannable avoids Android's default underline over the firm prefix.
        streamTypedHere.setLength(0);
        streamTypedHere.append(text);
        streamComposing = true;
        if (!ic.setComposingText(shown, 1)) {
            // Do not guess what an unsupported editor inserted.
            stopForEditorChange();
        }
    }

    private boolean prepareStreamRegion(InputConnection ic) {
        if (streamStart >= 0) return true;
        android.view.inputmethod.ExtractedText extracted = ic.getExtractedText(
                new android.view.inputmethod.ExtractedTextRequest(), 0);
        if (extracted == null || extracted.selectionStart < 0 || extracted.selectionEnd < 0) {
            streamDeferred = true;
            if (hintView != null) hintView.setText(streamCommitted + streamTentative);
            return false; // Conservative fallback: final insertion only.
        }
        CharSequence selected = ic.getSelectedText(0);
        String originalSelection = LiveSelectionSnapshot.capture(
                extracted.selectionStart, extracted.selectionEnd, selected);
        if (originalSelection == null) {
            // A range without a complete snapshot cannot be restored on
            // Cancel. Do not replace it; use final-only insertion instead.
            streamDeferred = true;
            if (hintView != null) hintView.setText(streamCommitted + streamTentative);
            return false;
        }
        streamStart = extracted.startOffset
                + Math.min(extracted.selectionStart, extracted.selectionEnd);
        streamOriginalEnd = extracted.startOffset
                + Math.max(extracted.selectionStart, extracted.selectionEnd);
        streamOriginalSelection = originalSelection;
        CharSequence before = ic.getTextBeforeCursor(1, 0);
        streamLead = before != null && before.length() > 0
                && !Character.isWhitespace(before.charAt(before.length() - 1)) ? " " : "";
        return true;
    }

    private boolean ownsStreamRegion(InputConnection ic) {
        CharSequence selected = ic.getSelectedText(0);
        if (selected != null && selected.length() > 0) return false;
        CharSequence before = ic.getTextBeforeCursor(streamTypedHere.length(), 0);
        return before != null && before.toString().equals(streamTypedHere.toString());
    }

    private void finishStream(String text) {
        InputConnection ic = getCurrentInputConnection();
        boolean anyText = !text.trim().isEmpty();
        if (inputActive && ic != null) {
            if (streamComposing && !ownsStreamRegion(ic)) {
                stopForEditorChange();
                return;
            }
            if (streamComposing) {
                String finalText = anyText ? streamLead + text.trim() + " "
                        : streamOriginalSelection;
                ic.beginBatchEdit();
                streamComposing = false;
                ic.commitText(finalText, 1);
                ic.finishComposingText();
                ic.endBatchEdit();
                if (anyText && !pendingSwitchBack
                        && new File(getFilesDir(), "select_transcription").exists()) {
                    selectBeforeCursor(ic, finalText.length());
                }
            } else if (anyText) {
                commitTranscribedText(ic, text.trim() + " ");
            }
        } else if (anyText) {
            pendingCommitText = text.trim() + " ";
        }
        resetStream();
        captureFinishing = false;
        releaseAudioFocus();
        updateRecordButtonUI(false);
        if (statusView != null) statusView.setText(anyText
                ? R.string.ime_tap_to_record : R.string.voice_no_speech);
        if (pendingSwitchBack) {
            pendingSwitchBack = false;
            switchAwayFromVoiceKeyboard();
        }
    }

    /** Remove the owned composition and restore the selection it replaced. */
    private void dropTentative(InputConnection ic) {
        if (!streamComposing) return;
        boolean owned = ownsStreamRegion(ic);
        streamComposing = false;
        if (owned) {
            // Some editors end composition before onFinishInput. Select the
            // verified owned range explicitly; never append the old selection.
            ic.finishComposingText();
            ic.setSelection(streamStart, streamStart + streamTypedHere.length());
            ic.commitText(streamOriginalSelection, 1);
            if (streamStart >= 0) ic.setSelection(streamStart, streamOriginalEnd);
        } else {
            ic.finishComposingText();
        }
        clearStreamRegion();
    }

    private void discardStreamText() {
        streamDiscarded = true;
        ++recordingGeneration;
        captureFinishing = false;
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) dropTentative(ic);
        resetStream();
    }

    /** User edits/Undo take priority. Keep their text; never reinsert a stale result. */
    private void stopForEditorChange() {
        if (!continuousTypingThisRecording) return;
        if (!streamActive && !isRecording && !captureFinishing) return;
        streamDiscarded = true;
        ++recordingGeneration;
        InputConnection ic = getCurrentInputConnection();
        streamComposing = false;
        if (ic != null) ic.finishComposingText();
        cancelRecording();
        resetStream();
        captureFinishing = false;
        pendingSwitchBack = false;
        lastStatus = "Ready";
        releaseAudioFocus();
        updateRecordButtonUI(false);
        updateUiState();
    }

    @Override
    public void onUpdateSelection(int oldStart, int oldEnd, int newStart, int newEnd,
            int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldStart, oldEnd, newStart, newEnd,
                candidatesStart, candidatesEnd);
        if (!streamComposing) return;
        if (candidatesStart == streamStart && candidatesEnd >= candidatesStart) {
            streamCompositionSeen = true;
            if (newStart != candidatesEnd || newEnd != candidatesEnd) stopForEditorChange();
        } else if (streamCompositionSeen && candidatesStart < 0) {
            stopForEditorChange();
        }
    }

    private void clearStreamRegion() {
        streamComposing = false;
        streamCompositionSeen = false;
        streamDeferred = false;
        streamStart = streamOriginalEnd = -1;
        streamOriginalSelection = "";
        streamTypedHere.setLength(0);
        streamLead = null;
    }

    private void resetStream() {
        streamActive = false;
        streamCommitted = "";
        streamTentative = "";
        clearStreamRegion();
    }

    public void onAudioLevel(float level) { handleLevel(recordingGeneration, level); }

    private void handleLevel(long generation, float level) {
        mainHandler.post(() -> {
            if (generation == recordingGeneration && micLevelView != null)
                micLevelView.setLevel(level);
        });
    }

    private boolean isPauseAudioEnabled() {
        return new File(getFilesDir(), "pause_audio").exists();
    }

    /** "Record in background" is default ON; the marker file is the opt-out. */
    private boolean isStopOnHideEnabled() {
        return new File(getFilesDir(), "stop_on_hide").exists();
    }
}
