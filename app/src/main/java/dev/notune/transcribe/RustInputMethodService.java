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
    // process audio. Flushed from onStartInputView once the *same* field is
    // focused again so text is never lost and never lands in another field.
    private String pendingCommitText = null;
    private EditorTarget pendingCommitTarget = null;
    private int pendingCommitSession = -1;

    // One dictation = one sequence number. Cancelling, hiding with
    // "stop on hide", or starting a new recording increments it, which makes
    // every late recognition or post-processing result stale and unusable.
    private int dictationSeq = 0;
    private int pendingTranscriptSeq = -1;
    private EditorTarget targetEditor = null;
    // Incremented on every onStartInput. When a field exposes no stable view id
    // or field name, two different fields can carry identical EditorInfo; an
    // unchanged session is then the only evidence that it is still the same
    // editor. The dictation remembers the session it started in.
    private int inputSession = 0;
    private int targetInputSession = -1;
    // Between "stop" and the final text callback, the record button must stay
    // disabled: otherwise a new recording could start and silently discard the
    // text that is about to arrive.
    private boolean awaitingTranscript = false;

    private PostProcessor.Task postProcessingTask = null;
    private boolean postProcessingActive = false;
    // Bumped when a new dictation starts, so an old "notice" cannot reset the
    // status line over the new state.
    private int noticeToken = 0;

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
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) {
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
                    beginDictation();
                    if (isPauseAudioEnabled()) {
                        audioPauser.request(this);
                        pauseAudioActive = true;
                    }
                    startRecording(dictationSeq);
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
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL));
            ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DEL));
        }
    }

    private void insertSpace() {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.commitText(" ", 1);
        }
    }

    /**
     * Stops capture and hands the audio to transcription. Audio focus is
     * released right away, so paused media can resume while the (possibly
     * long) transcription runs.
     */
    private void finishCapture() {
        if (!isRecording) return;
        // The text callback may arrive after the user has already moved on.
        // Remember which dictation it belongs to.
        pendingTranscriptSeq = dictationSeq;
        awaitingTranscript = true;
        stopRecording();
        releaseAudioFocus();
        updateRecordButtonUI(false);
        updateUiState();
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
        if (awaitingTranscript || postProcessingActive) {
            // The previous dictation is still finishing. Starting another one
            // now would silently discard its text.
            updateUiState();
            return;
        }
        if (new File(getFilesDir(), "auto_record").exists()) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                beginDictation();
                if (isPauseAudioEnabled()) {
                    audioPauser.request(this);
                    pauseAudioActive = true;
                }
                startRecording(dictationSeq);
                updateRecordButtonUI(true);
            }
        }
    }

    @Override
    public void onWindowHidden() {
        super.onWindowHidden();
        windowVisible = false;
        if (!isRecording && (awaitingTranscript || postProcessingActive) && isStopOnHideEnabled()) {
            // "Stop on hide" also means: do not paste a result after the user
            // has left the field. This covers the gap between stopping the
            // recording and the transcript arriving, before post-processing
            // has started.
            invalidateDictation();
            updateUiState();
        } else if (isRecording) {
            if (isStopOnHideEnabled()) {
                // Opt-in behavior: discard the recording when the keyboard hides.
                try {
                    cancelRecording();
                } catch (Throwable t) {
                    Log.w(TAG, "cancelRecording failed, falling back to stopRecording", t);
                    try { stopRecording(); } catch (Throwable ignored) { }
                }
                invalidateDictation();
                updateRecordButtonUI(false);
                updateUiState();
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
        inputSession++;
        inputActive = isTextEditor(attribute);

        // A field change makes both an in-flight clean-up and text deferred for
        // the previous field stale. Drop them instead of pasting into whatever
        // is focused now.
        if (inputActive && postProcessingTask != null && targetEditor != null
                && !isSameField(targetEditor, attribute, targetInputSession)) {
            cancelPostProcessing();
            showNotice(getString(R.string.pp_field_changed));
            updateUiState();
        }
        if (inputActive && pendingCommitText != null && pendingCommitTarget != null
                && !isSameField(pendingCommitTarget, attribute, pendingCommitSession)) {
            pendingCommitText = null;
            pendingCommitTarget = null;
            pendingCommitSession = -1;
            showNotice(getString(R.string.pp_field_changed));
        }
    }

    /**
     * Conservative field identity. A stable view id or field name is trusted;
     * without one, only an unchanged input session confirms that this is still
     * the editor the dictation started in.
     */
    private boolean isSameField(EditorTarget target, EditorInfo info, int session) {
        if (target == null) return session == inputSession;
        return target.matches(info)
                && (target.hasStrongIdentity() || session == inputSession);
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
    }

    @Override
    public void onFinishInput() {
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
        cancelPostProcessing();
        cleanupNative(this);
        releaseAudioFocus();
    }

    // Native methods
    private native void initNative(RustInputMethodService service);
    private native void cleanupNative(RustInputMethodService self);
    // The dictation number is echoed back with the transcript so a late
    // result from an aborted dictation can never be used for a newer one.
    private native void startRecording(int captureId);
    private native void stopRecording();
    private native void cancelRecording();

    // Called from Rust
    public void onStatusUpdate(String status) {
        mainHandler.post(() -> {
            Log.d(TAG, "Status: " + status);
            lastStatus = status;
            // No pending state is cleared here: a status can arrive late from
            // an aborted dictation. Completion of the capture that is actually
            // awaited comes through onTextTranscribed or
            // onTranscriptionFinished, both carrying its dictation number.
            updateUiState();
            if (pendingSwitchBack && status != null && status.startsWith("Error")) {
                pendingSwitchBack = false;
                switchAwayFromVoiceKeyboard();
            }
            if (status != null && status.startsWith("Error")) {
                releaseAudioFocus();
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
        boolean busy = isTranscribing || isWaiting || awaitingTranscript || postProcessingActive;

        // Don't show internal loading states to the user
        if (statusView != null && !isRecording) {
            if (isError && !lastStatus.equals(ModelStore.NO_MODEL_STATUS)) {
                statusView.setText(lastStatus);
            } else if (postProcessingActive) {
                statusView.setText(R.string.pp_processing);
            } else if (busy) {
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
            recordContainer.setEnabled(!busy);
            recordContainer.setAlpha(busy ? 0.5f : 1.0f);
        }

        if (hintView != null && !isRecording) {
            if (postProcessingActive) {
                hintView.setText(R.string.pp_processing_hint);
            } else {
                hintView.setText(noModel ? R.string.ime_no_model_hint : R.string.ime_tap_to_record);
            }
        }
    }

    // Called from Rust with the dictation number its capture was started with.
    public void onTextTranscribed(String text, int captureId) {
        mainHandler.post(() -> {
            if (captureId != pendingTranscriptSeq || captureId != dictationSeq) {
                // A new recording, a cancel or "stop on hide" replaced the
                // capture that produced this text. Do not touch the current
                // state: this stale result can arrive while a newer dictation
                // is still recording, and clearing isRecording here would
                // leave the live microphone running behind an idle button.
                return;
            }
            int seq = captureId;
            pendingTranscriptSeq = -1;
            awaitingTranscript = false;

            if (text == null || text.trim().isEmpty()) {
                // Nothing recognized: don't insert a stray space.
                handleNoSpeech();
                return;
            }

            if (!PostProcessingSettings.isEnabled(this)) {
                deliverDictationText(text, seq, targetEditor);
                return;
            }
            if (!PostProcessingSettings.isConfigured(this)) {
                deliverDictationText(text, seq, targetEditor);
                showNotice(getString(R.string.pp_not_configured));
                return;
            }
            startPostProcessing(text, seq, targetEditor);
        });
    }

    /**
     * Called from Rust when a stopped capture produced no text (empty audio,
     * model failure, inference error). Only the capture that is still awaited
     * may clear the waiting state, so a late notice from an aborted dictation
     * cannot unblock the button of a newer one.
     */
    public void onTranscriptionFinished(int captureId) {
        mainHandler.post(() -> {
            if (captureId != pendingTranscriptSeq) return;
            pendingTranscriptSeq = -1;
            awaitingTranscript = false;
            updateRecordButtonUI(false);
            updateUiState();
        });
    }

    /**
     * Runs the optional clean-up request. The raw text is kept aside: every
     * failure path (network, timeout, HTTP error, truncated or empty answer)
     * inserts it unchanged.
     */
    private void startPostProcessing(String rawText, int seq, EditorTarget target) {
        postProcessingActive = true;
        updateUiState();

        postProcessingTask = PostProcessor.run(this, rawText, (cleaned, error) -> {
            // The callback is on the main thread already.
            if (seq != dictationSeq) {
                // Post-processing was cancelled or a new dictation started;
                // PostProcessor normally suppresses this, but check anyway.
                return;
            }
            postProcessingTask = null;
            postProcessingActive = false;

            if (error != null) {
                deliverDictationText(rawText, seq, target);
                showNotice(getString(R.string.pp_failed_fallback,
                        PostProcessingLogic.shortError(error)));
                return;
            }
            // Some models answer "эээ, ммм" with the same filler sounds
            // instead of an empty string. Never insert those; if content was
            // lost, keep the raw transcript instead.
            boolean usable = cleaned != null && !cleaned.trim().isEmpty()
                    && !PostProcessingLogic.isFillerOnly(cleaned);
            if (!usable) {
                if (PostProcessingLogic.isFillerOnly(rawText)) {
                    handleNoSpeech();
                } else {
                    deliverDictationText(rawText, seq, target);
                    showNotice(getString(R.string.pp_empty_fallback));
                }
                return;
            }
            deliverDictationText(cleaned, seq, target);
        });
    }

    /**
     * Inserts text in the field the dictation started in. If that field is gone
     * or a different one is focused, the text is dropped (or deferred for the
     * same field when no editor is live right now) instead of being pasted
     * into the wrong place.
     */
    private void deliverDictationText(String text, int seq, EditorTarget target) {
        if (seq != dictationSeq) return;
        if (text == null || text.trim().isEmpty()) {
            handleNoSpeech();
            return;
        }

        InputConnection ic = getCurrentInputConnection();
        EditorInfo info = getCurrentInputEditorInfo();
        boolean editorActive = inputActive && ic != null && isTextEditor(info);

        if (editorActive && isSameField(target, info, targetInputSession)) {
            commitTranscribedText(ic, text + " ");
            finishDictationUi();
            return;
        }

        if (editorActive) {
            finishDictationUi();
            showNotice(getString(R.string.pp_field_changed));
            return;
        }

        // No editor is focused right now (common on long transcribes where a
        // web field in Firefox/Gemini dropped focus while we processed audio).
        // Hold the text for the same field instead of losing it.
        pendingCommitText = text + " ";
        pendingCommitTarget = target;
        pendingCommitSession = targetInputSession;
        finishDictationUi();
        if (!pendingSwitchBack) {
            showNotice(getString(R.string.pp_deferred));
        }
    }

    private void handleNoSpeech() {
        awaitingTranscript = false;
        lastStatus = "Ready";
        updateRecordButtonUI(false);
        updateUiState();
        if (statusView != null) statusView.setText(R.string.voice_no_speech);
        if (pendingSwitchBack) {
            pendingSwitchBack = false;
            switchAwayFromVoiceKeyboard();
        }
    }

    private void finishDictationUi() {
        awaitingTranscript = false;
        lastStatus = "Ready";
        releaseAudioFocus();
        updateRecordButtonUI(false);
        updateUiState();
        if (pendingSwitchBack) {
            pendingSwitchBack = false;
            switchAwayFromVoiceKeyboard();
        }
    }

    /**
     * Shows a short explanation and returns to the idle status after a few
     * seconds. The token makes sure a newer status (or a new recording) is not
     * overwritten by an older timer.
     */
    private void showNotice(String message) {
        if (statusView == null) return;
        final int token = ++noticeToken;
        statusView.setText(message);
        mainHandler.postDelayed(() -> {
            if (token != noticeToken || isRecording || postProcessingActive || awaitingTranscript) {
                return;
            }
            lastStatus = "Ready";
            updateUiState();
        }, 6000);
    }

    /** Starts a new dictation and makes every older result stale. */
    private void beginDictation() {
        dictationSeq++;
        pendingTranscriptSeq = -1;
        awaitingTranscript = false;
        noticeToken++;
        cancelPostProcessing();
        targetEditor = EditorTarget.from(getCurrentInputEditorInfo());
        targetInputSession = inputSession;
    }

    /** Cancels the current dictation and every late result it could produce. */
    private void invalidateDictation() {
        dictationSeq++;
        pendingTranscriptSeq = -1;
        awaitingTranscript = false;
        noticeToken++;
        cancelPostProcessing();
    }

    private void cancelPostProcessing() {
        if (postProcessingTask != null) {
            postProcessingTask.cancel();
            postProcessingTask = null;
        }
        postProcessingActive = false;
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
            android.view.inputmethod.ExtractedText et = ic.getExtractedText(
                new android.view.inputmethod.ExtractedTextRequest(), 0);
            if (et != null) {
                int end = et.startOffset + et.selectionStart;
                int start = end - committed.length();
                if (start >= 0) {
                    ic.setSelection(start, end);
                }
            }
        }
    }

    // Commits text that finished transcribing while no field was focused. Called
    // from onStartInputView when an editor (and a live input connection) is
    // available again. Only the field the dictation started in may receive it.
    private void flushPendingText() {
        if (pendingCommitText == null) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        EditorInfo info = getCurrentInputEditorInfo();
        if (!isSameField(pendingCommitTarget, info, pendingCommitSession)) {
            pendingCommitText = null;
            pendingCommitTarget = null;
            pendingCommitSession = -1;
            showNotice(getString(R.string.pp_field_changed));
            return;
        }
        commitTranscribedText(ic, pendingCommitText);
        pendingCommitText = null;
        pendingCommitTarget = null;
        pendingCommitSession = -1;
    }

    public void onAudioLevel(float level) {
        if (micLevelView != null) {
            mainHandler.post(() -> micLevelView.setLevel(level));
        }
    }

    private boolean isPauseAudioEnabled() {
        return new File(getFilesDir(), "pause_audio").exists();
    }

    /** "Record in background" is default ON; the marker file is the opt-out. */
    private boolean isStopOnHideEnabled() {
        return new File(getFilesDir(), "stop_on_hide").exists();
    }
}
