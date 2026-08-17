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
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.content.Context;
import android.content.pm.PackageManager;
import android.view.MotionEvent;
import android.view.inputmethod.EditorInfo;
import android.content.res.ColorStateList;
import android.view.ContextThemeWrapper;
import java.io.File;
import java.text.BreakIterator;
import java.util.Locale;

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
    private View backspaceNavButton;
    private View spaceButton;
    private View enterButton;
    private View switchKeyboardButton;
    private View inputView;
    private MicLevelView micLevelView;
    private View recordCircle;
    // Optional text-editing keys (see the settings on the main screen).
    private View shiftButton;
    private View wordLeftButton;
    private View charLeftButton;
    private View charRightButton;
    private View wordRightButton;
    private View selectButton;
    private View commaButton;
    private View periodButton;
    // Night flag the current input view was inflated with, so it can be rebuilt
    // if the theme preference changes while this process stays alive.
    private boolean viewIsNight = false;
    // Same idea for the two optional-key settings: they change the layout, and
    // this process outlives the app screen where they are toggled.
    private boolean viewKbKeyTop = false;
    private boolean viewEditRow = false;
    private Handler mainHandler;
    private boolean isRecording = false;
    private boolean pendingSwitchBack = false;
    private String lastStatus = "Initializing...";
    // Key repeat settings
    private static final long REPEAT_INITIAL_DELAY = 400; // ms before repeat starts
    private static final long REPEAT_INTERVAL = 50; // ms between repeats
    private Runnable backspaceRepeatRunnable;
    private Runnable spaceRepeatRunnable;
    // One per direction: sharing a single field would let a second finger
    // overwrite the first key's runnable, orphaning it to repeat forever.
    private Runnable charLeftRepeatRunnable;
    private Runnable charRightRepeatRunnable;
    // How much text either side of the cursor to inspect for word and paragraph
    // jumps. Editors only share a window of their content with the IME, so this
    // is a bound on the search, not on the field.
    private static final int EDIT_TEXT_WINDOW = 4096;
    // Cursor position, kept current by onUpdateSelection.
    private int selStart = 0;
    private int selEnd = 0;
    // The range this service last asked for. onUpdateSelection compares against
    // it to tell its own moves from the user tapping into the text, which has to
    // cancel the cycles below — they are anchored to the previous selection.
    private int expectedSelStart = -1;
    private int expectedSelEnd = -1;
    // Set when we asked for a selection whose result only the editor knows
    // (select-all), so the one update it produces isn't mistaken for the user's.
    private boolean selectionChangePending = false;
    // While on, the cursor keys extend the selection from selectionAnchor
    // instead of moving the caret.
    private boolean selectionMode = false;
    private int selectionAnchor = 0;
    // Select key: 1 = last dictation, 2 = whole field, 0 = nothing (restoring
    // the caret to where it was when the cycle started).
    private int selectCycleStep = 0;
    private int selectCycleCaret = 0;
    // Range of the last committed transcription, or -1 once any edit has made
    // the offsets meaningless.
    private int lastDictationStart = -1;
    private int lastDictationEnd = -1;
    // Case key: 1 = capitalized, 2 = upper, 3 = lower. Each step is computed
    // from the text as it was before the cycle started, so capitalizing an
    // already-uppercase word still works.
    private int caseCycleStep = 0;
    private String caseCycleOriginal = null;
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
            // The IME is a non-AppCompat Service in a separate process, so
            // AppCompat's delegate can't theme it. Build a context that is
            // night-aware (per the saved preference), wears the Material 3 theme,
            // and picks up Material You dynamic color — matching the app.
            Context night = ThemePrefs.wrapForNight(this, ThemePrefs.getMode(this));
            viewIsNight = ThemePrefs.isNight(night);
            Context themed = DynamicColors.wrapContextIfAvailable(
                    new ContextThemeWrapper(night, R.style.AppTheme));
            View view = LayoutInflater.from(themed).inflate(R.layout.ime_layout, null);
            inputView = view;

            // Handle window insets for avoiding navigation bar overlap
            view.setOnApplyWindowInsetsListener((v, insets) -> {
                int paddingBottom = insets.getSystemWindowInsetBottom();
                int originalPaddingBottom = v.getPaddingTop();
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), originalPaddingBottom + paddingBottom);
                return insets;
            });

            // Which optional keys to show. Captured here so onStartInputView can
            // tell when the user has changed them behind this view's back.
            viewKbKeyTop = isKbKeyTopEnabled();
            viewEditRow = isEditRowEnabled();

            statusView = view.findViewById(R.id.ime_status_text);
            progressBar = view.findViewById(R.id.ime_progress);
            recordContainer = view.findViewById(R.id.ime_record_container);
            micIcon = view.findViewById(R.id.ime_mic_icon);
            micLevelView = view.findViewById(R.id.ime_mic_level);
            recordCircle = view.findViewById(R.id.ime_record_circle);
            hintView = view.findViewById(R.id.ime_hint);
            backspaceButton = view.findViewById(R.id.ime_backspace);
            backspaceNavButton = view.findViewById(R.id.ime_backspace_nav);
            spaceButton = view.findViewById(R.id.ime_space);
            enterButton = view.findViewById(R.id.ime_enter);
            switchKeyboardButton = view.findViewById(R.id.ime_switch_keyboard);
            shiftButton = view.findViewById(R.id.ime_shift);
            wordLeftButton = view.findViewById(R.id.ime_word_left);
            charLeftButton = view.findViewById(R.id.ime_char_left);
            charRightButton = view.findViewById(R.id.ime_char_right);
            wordRightButton = view.findViewById(R.id.ime_word_right);
            selectButton = view.findViewById(R.id.ime_select);
            commaButton = view.findViewById(R.id.ime_comma);
            periodButton = view.findViewById(R.id.ime_period);

            applyKeyLayout(view);

            // Same action from either position, depending on the setting.
            View.OnClickListener switchKeyboard = v -> {
                if (isRecording) {
                    pendingSwitchBack = true;
                    stopRecording();
                    updateRecordButtonUI(false);
                } else {
                    switchToPreviousInputMethod();
                }
            };
            switchKeyboardButton.setOnClickListener(switchKeyboard);
            view.findViewById(R.id.ime_switch_keyboard_top).setOnClickListener(switchKeyboard);

            // Every key is wired whether or not it is currently shown, so that
            // toggling a setting is a visibility change on the live view rather
            // than a rebuild.
            wireEditingKeys();

            // Key repeat runnable for backspace
            backspaceRepeatRunnable = new Runnable() {
                @Override
                public void run() {
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null) {
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL));
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DEL));
                    }
                    mainHandler.postDelayed(this, REPEAT_INTERVAL);
                }
            };

            // Key repeat runnable for space
            spaceRepeatRunnable = new Runnable() {
                @Override
                public void run() {
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null) {
                        ic.commitText(" ", 1);
                    }
                    mainHandler.postDelayed(this, REPEAT_INTERVAL);
                }
            };

            // Backspace exists in both rows; only one of them is ever visible.
            View.OnTouchListener backspaceTouch = (v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        onTextEdited();
                        InputConnection ic = getCurrentInputConnection();
                        if (ic != null) {
                            ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL));
                            ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DEL));
                        }
                        mainHandler.postDelayed(backspaceRepeatRunnable, REPEAT_INITIAL_DELAY);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        mainHandler.removeCallbacks(backspaceRepeatRunnable);
                        return true;
                }
                return false;
            };
            backspaceButton.setOnTouchListener(backspaceTouch);
            backspaceNavButton.setOnTouchListener(backspaceTouch);

            spaceButton.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        onTextEdited();
                        InputConnection ic = getCurrentInputConnection();
                        if (ic != null) {
                            ic.commitText(" ", 1);
                        }
                        mainHandler.postDelayed(spaceRepeatRunnable, REPEAT_INITIAL_DELAY);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        mainHandler.removeCallbacks(spaceRepeatRunnable);
                        return true;
                }
                return false;
            });

            enterButton.setOnClickListener(v -> {
                onTextEdited();
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
                    if (statusView != null) statusView.setText("No mic permission - grant in app");
                    if (hintView != null) hintView.setText("Open the app to grant permission");
                    return;
                }

                if (isRecording) {
                    stopRecording();
                    if (pauseAudioActive) {
                        audioPauser.abandon(this);
                        pauseAudioActive = false;
                    }
                    updateRecordButtonUI(false);
                } else {
                    if (isPauseAudioEnabled()) {
                        audioPauser.request(this);
                        pauseAudioActive = true;
                    }
                    startRecording();
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
        if (new File(getFilesDir(), "auto_record").exists()) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                if (isPauseAudioEnabled()) {
                    audioPauser.request(this);
                    pauseAudioActive = true;
                }
                startRecording();
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
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        inputActive = true;
        // A different field means the remembered offsets point at nothing, and a
        // selection mode left on from the last field would surprise the user.
        resetEditingState(attribute);
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        inputActive = true;
        // Both the theme and the optional-key settings can change while this
        // (long-lived) IME process stays alive, so check them every time the
        // keyboard is about to be shown.
        boolean night = ThemePrefs.isNight(ThemePrefs.wrapForNight(this, ThemePrefs.getMode(this)));
        if (inputView != null && night != viewIsNight) {
            // A theme change needs a fresh inflate to re-resolve every attribute.
            cancelKeyRepeats();
            setInputView(onCreateInputView());
        } else if (inputView != null
                && (isKbKeyTopEnabled() != viewKbKeyTop || isEditRowEnabled() != viewEditRow)) {
            // The keys are all inflated already, so this is only a visibility
            // change. Swapping in a whole new view here would leave the added row
            // undrawn, because the input window has already been sized.
            viewKbKeyTop = isKbKeyTopEnabled();
            viewEditRow = isEditRowEnabled();
            cancelKeyRepeats();
            applyKeyLayout(inputView);
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

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd,
                                  int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                candidatesStart, candidatesEnd);
        selStart = newSelStart;
        selEnd = newSelEnd;
        // Someone other than us moved the caret — the user tapped into the text,
        // or the app edited it. Both cycles remember a range and a snapshot of
        // the text that was in it, so continuing them would apply the old text
        // to the new selection.
        if (selectionChangePending) {
            selectionChangePending = false;
            expectedSelStart = newSelStart;
            expectedSelEnd = newSelEnd;
        } else if (newSelStart != expectedSelStart || newSelEnd != expectedSelEnd) {
            selectCycleStep = 0;
            caseCycleStep = 0;
        }
    }

    /**
     * Moves the selection and records where we put it, so the mirror above stays
     * in step and onUpdateSelection can recognise the change as ours.
     */
    private void setSelectionTracked(InputConnection ic, int start, int end) {
        ic.setSelection(start, end);
        selStart = expectedSelStart = start;
        selEnd = expectedSelEnd = end;
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
            // With the editing keys on there is no room for the hint under the
            // mic, so the status line carries the instruction instead.
            statusView.setText(viewEditRow ? "Listening… (tap to stop)" : "Listening...");
            hintView.setText("Tap to Stop");
        } else {
            statusView.setText("Processing...");
            hintView.setText("Tap to Record");
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
        cleanupNative();
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
    }

    // Native methods
    private native void initNative(RustInputMethodService service);
    private native void cleanupNative();
    private native void startRecording();
    private native void stopRecording();
    private native void cancelRecording();

    // Called from Rust
    public void onStatusUpdate(String status) {
        mainHandler.post(() -> {
            Log.d(TAG, "Status: " + status);
            lastStatus = status;
            updateUiState();
            if (pendingSwitchBack && status.startsWith("Error")) {
                pendingSwitchBack = false;
                switchToPreviousInputMethod();
            }
            if (pauseAudioActive && status != null && status.startsWith("Error")) {
                audioPauser.abandon(this);
                pauseAudioActive = false;
            }
        });
    }

    private void updateUiState() {
        boolean isLoading = lastStatus.contains("Loading") || lastStatus.contains("Initializing");
        boolean isWaiting = lastStatus.contains("Waiting");
        boolean isTranscribing = lastStatus.contains("Transcribing") || lastStatus.contains("Processing");
        boolean isError = lastStatus.startsWith("Error");
        boolean isReady = lastStatus.equals("Ready");

        // Don't show internal loading states to the user
        if (statusView != null && !isRecording) {
            if (isError) {
                statusView.setText(lastStatus);
            } else if (isTranscribing || isWaiting) {
                statusView.setText("Processing...");
            } else {
                statusView.setText("Tap to Record");
            }
        }

        // Hide progress bar - don't expose model loading to user
        if (progressBar != null) {
            progressBar.setVisibility(View.GONE);
        }

        // Disable button only during transcription/processing/waiting or fatal errors
        if (recordContainer != null) {
            boolean disable = isTranscribing || isWaiting || isError;
            recordContainer.setEnabled(!disable);
            recordContainer.setAlpha(disable ? 0.5f : 1.0f);
        }

        if (hintView != null && !isRecording) {
            hintView.setText("Tap to Record");
        }
    }

    // Called from Rust
    public void onTextTranscribed(String text) {
        mainHandler.post(() -> {
            if (text == null || text.trim().isEmpty()) {
                // Nothing recognized — don't insert a stray space.
                updateRecordButtonUI(false);
                if (statusView != null) statusView.setText("Tap to Record");
                if (pauseAudioActive) {
                    audioPauser.abandon(this);
                    pauseAudioActive = false;
                }
                if (pendingSwitchBack) {
                    pendingSwitchBack = false;
                    switchToPreviousInputMethod();
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
            if (pauseAudioActive) {
                audioPauser.abandon(this);
                pauseAudioActive = false;
            }
            updateRecordButtonUI(false);
            if (statusView != null) statusView.setText("Tap to Record");
            if (pendingSwitchBack) {
                pendingSwitchBack = false;
                switchToPreviousInputMethod();
            }
        });
    }

    // Commits transcribed text into the active input connection, optionally
    // selecting it afterwards (select_transcription setting).
    private void commitTranscribedText(InputConnection ic, String committed) {
        // Dictating over a selection replaces it, so any selection mode the user
        // left on has served its purpose by now, and both cycles are anchored to
        // text that is no longer there.
        if (selectionMode) {
            selectionMode = false;
            updateEditingKeyTints();
        }
        selectCycleStep = 0;
        caseCycleStep = 0;
        ic.commitText(committed, 1);

        // Extracting the field is a round trip that copies its text, so only ask
        // when something will read the answer: the select key's "just what I
        // dictated" step, or the select-transcription setting.
        boolean selectTranscription = !pendingSwitchBack
                && new File(getFilesDir(), "select_transcription").exists();
        if (!viewEditRow && !selectTranscription) return;

        android.view.inputmethod.ExtractedText et = ic.getExtractedText(
            new android.view.inputmethod.ExtractedTextRequest(), 0);
        if (et != null) {
            int end = et.selectionStart;
            int start = end - committed.length();
            // Remembered so the select key can offer "just what I dictated"
            // before falling back to the whole field.
            lastDictationStart = start >= 0 ? start : -1;
            lastDictationEnd = start >= 0 ? end : -1;
            if (selectTranscription && start >= 0) {
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
    public void onAudioLevel(float level) {
        if (micLevelView != null) {
            mainHandler.post(() -> micLevelView.setLevel(level));
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * Shows the keys the user has opted into. Every key is inflated either way
     * (see ime_layout.xml), so this only flips visibility — no findViewById can
     * come back null for an arrangement that isn't in use.
     */
    private void applyKeyLayout(View view) {
        view.findViewById(R.id.ime_switch_keyboard_top)
                .setVisibility(viewKbKeyTop ? View.VISIBLE : View.GONE);
        switchKeyboardButton.setVisibility(viewKbKeyTop ? View.GONE : View.VISIBLE);

        view.findViewById(R.id.ime_nav_row).setVisibility(viewEditRow ? View.VISIBLE : View.GONE);
        selectButton.setVisibility(viewEditRow ? View.VISIBLE : View.GONE);
        commaButton.setVisibility(viewEditRow ? View.VISIBLE : View.GONE);
        periodButton.setVisibility(viewEditRow ? View.VISIBLE : View.GONE);
        // Backspace moved up to the cursor row; the status line takes over the
        // hint's "tap to stop" duty.
        view.findViewById(R.id.ime_backspace)
                .setVisibility(viewEditRow ? View.GONE : View.VISIBLE);
        hintView.setVisibility(viewEditRow ? View.GONE : View.VISIBLE);

        // Enter ends the bottom row once backspace leaves it, so drop the
        // trailing gap that would otherwise sit against the edge.
        LinearLayout.LayoutParams enterParams =
                (LinearLayout.LayoutParams) enterButton.getLayoutParams();
        enterParams.width = dp(viewEditRow ? 52 : 64);
        enterParams.setMarginEnd(viewEditRow ? 0 : dp(8));
        enterButton.setLayoutParams(enterParams);

        // The optional rows take their height out of the record area rather than
        // adding to it, so the keyboard never grows and shoves the host app's
        // layout upward.
        android.view.ViewGroup.LayoutParams recordParams = recordContainer.getLayoutParams();
        recordParams.height = dp(viewEditRow ? 124 : (viewKbKeyTop ? 176 : 200));
        recordContainer.setLayoutParams(recordParams);
    }

    /** Click handlers for the optional cursor, selection and punctuation keys. */
    private void wireEditingKeys() {
        charLeftButton.setOnTouchListener(cursorRepeatListener(-1));
        charRightButton.setOnTouchListener(cursorRepeatListener(1));

        wordLeftButton.setOnClickListener(v -> moveCaret(-1, BY_WORD));
        wordRightButton.setOnClickListener(v -> moveCaret(1, BY_WORD));
        // Holding a word key overshoots to the paragraph edge. This is why the
        // word keys don't repeat on hold the way the character keys do.
        wordLeftButton.setOnLongClickListener(v -> {
            moveCaret(-1, BY_PARAGRAPH);
            return true;
        });
        wordRightButton.setOnLongClickListener(v -> {
            moveCaret(1, BY_PARAGRAPH);
            return true;
        });

        selectButton.setOnClickListener(v -> onSelectKey());
        // Returning true both consumes the click that would otherwise follow on
        // release and gets the long-press haptic for free.
        selectButton.setOnLongClickListener(v -> {
            toggleSelectionMode();
            return true;
        });

        shiftButton.setOnClickListener(v -> onCaseKey());

        commaButton.setOnClickListener(v -> commitCharacter(","));
        periodButton.setOnClickListener(v -> commitCharacter("."));
        commaButton.setOnLongClickListener(v -> {
            commitCharacter("?");
            return true;
        });
        periodButton.setOnLongClickListener(v -> {
            commitCharacter("!");
            return true;
        });
    }

    /**
     * Hold-to-repeat for one cursor key. The runnable is created once and owned
     * by that key, mirroring the backspace and space keys: a runnable created
     * per touch and stored in a field shared with the opposite key is orphaned
     * when the other key is pressed, and then repeats until the process dies.
     */
    private View.OnTouchListener cursorRepeatListener(int direction) {
        Runnable repeat = new Runnable() {
            @Override
            public void run() {
                moveCaret(direction, BY_CHAR);
                mainHandler.postDelayed(this, REPEAT_INTERVAL);
            }
        };
        if (direction < 0) {
            charLeftRepeatRunnable = repeat;
        } else {
            charRightRepeatRunnable = repeat;
        }
        return (v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    moveCaret(direction, BY_CHAR);
                    mainHandler.postDelayed(repeat, REPEAT_INITIAL_DELAY);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mainHandler.removeCallbacks(repeat);
                    return true;
            }
            return false;
        };
    }

    private void cancelKeyRepeats() {
        if (backspaceRepeatRunnable != null) mainHandler.removeCallbacks(backspaceRepeatRunnable);
        if (spaceRepeatRunnable != null) mainHandler.removeCallbacks(spaceRepeatRunnable);
        if (charLeftRepeatRunnable != null) mainHandler.removeCallbacks(charLeftRepeatRunnable);
        if (charRightRepeatRunnable != null) mainHandler.removeCallbacks(charRightRepeatRunnable);
    }

    private static final int BY_CHAR = 0;
    private static final int BY_WORD = 1;
    private static final int BY_PARAGRAPH = 2;

    /**
     * Moves the caret, or extends the selection while selection mode is on.
     * With a selection but no selection mode the keys collapse it to the edge
     * they point at, which is what every text editor does.
     */
    private void moveCaret(int direction, int granularity) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        selectCycleStep = 0;
        caseCycleStep = 0;

        if (!selectionMode && selStart != selEnd) {
            int edge = direction > 0 ? Math.max(selStart, selEnd) : Math.min(selStart, selEnd);
            setSelectionTracked(ic, edge, edge);
            updateEditingKeyTints();
            return;
        }

        // A plain character step is best left to the editor: it knows where the
        // text ends and needs no round trip to read it.
        if (granularity == BY_CHAR && !selectionMode) {
            sendDownUpKeyEvents(direction > 0
                    ? android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                    : android.view.KeyEvent.KEYCODE_DPAD_LEFT);
            return;
        }

        int caret = selectionMode && selStart != selectionAnchor ? selStart : selEnd;
        int target;
        if (granularity == BY_CHAR) {
            // Extending past the last character: setSelection would be ignored
            // out of range and no correction would arrive, so the mirrored end
            // would drift past the text and the opposite key would look dead.
            if (direction > 0 && caret >= selEnd) {
                CharSequence after = ic.getTextAfterCursor(1, 0);
                if (after == null || after.length() == 0) return;
            }
            target = Math.max(0, caret + direction);
        } else {
            android.view.inputmethod.ExtractedTextRequest req =
                    new android.view.inputmethod.ExtractedTextRequest();
            // Bound what the editor has to marshal; startOffset below puts the
            // window back into whole-field coordinates.
            req.hintMaxChars = EDIT_TEXT_WINDOW;
            android.view.inputmethod.ExtractedText et = ic.getExtractedText(req, 0);
            if (et == null || et.text == null) {
                // Password fields and editors with their own text handling don't
                // extract; fall back to a character step rather than doing nothing.
                target = Math.max(0, caret + direction);
            } else {
                int offset = Math.max(0, et.startOffset);
                String text = et.text.toString();
                int local = Math.max(0, Math.min(text.length(), caret - offset));
                target = offset + (granularity == BY_WORD
                        ? wordBoundary(text, local, direction)
                        : paragraphEdge(text, local, direction));
            }
        }

        if (selectionMode) {
            setSelectionTracked(ic, Math.min(selectionAnchor, target),
                    Math.max(selectionAnchor, target));
        } else {
            setSelectionTracked(ic, target, target);
        }
    }

    /**
     * Next word boundary in {@code text} from {@code from}. Separator runs are
     * skipped so that a jump always lands on a word rather than on the space
     * before it. BreakIterator does the locale-specific part.
     */
    private int wordBoundary(String text, int from, int direction) {
        BreakIterator it = BreakIterator.getWordInstance(Locale.getDefault());
        it.setText(text);
        int pos = from;
        while (true) {
            int next = direction < 0 ? it.preceding(pos) : it.following(pos);
            if (next == BreakIterator.DONE) return direction < 0 ? 0 : text.length();
            if (hasLetterOrDigit(text, Math.min(pos, next), Math.max(pos, next))) return next;
            pos = next;
        }
    }

    private boolean hasLetterOrDigit(String text, int from, int to) {
        for (int i = from; i < to; i++) {
            if (Character.isLetterOrDigit(text.charAt(i))) return true;
        }
        return false;
    }

    /** Start or end of the current paragraph; the whole field if it has no newlines. */
    private int paragraphEdge(String text, int from, int direction) {
        if (direction < 0) {
            int nl = text.lastIndexOf('\n', Math.max(0, from - 1));
            return nl < 0 ? 0 : nl + 1;
        }
        int nl = text.indexOf('\n', from);
        return nl < 0 ? text.length() : nl;
    }

    /**
     * Select key: successive taps widen the selection from the last dictation to
     * the whole field, then put the caret back where the cycle started.
     */
    private void onSelectKey() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        caseCycleStep = 0;
        if (selectionMode) {
            // Also the way out of selection mode, so the key is never a dead end.
            selectionMode = false;
            updateEditingKeyTints();
            return;
        }

        if (selectCycleStep == 0) selectCycleCaret = selStart;
        selectCycleStep++;

        if (selectCycleStep == 1) {
            if (lastDictationStart >= 0 && lastDictationEnd > lastDictationStart) {
                setSelectionTracked(ic, lastDictationStart, lastDictationEnd);
                return;
            }
            // Nothing dictated into this field yet, so there is no first step.
            selectCycleStep = 2;
        }
        if (selectCycleStep == 2) {
            ic.performContextMenuAction(android.R.id.selectAll);
            selectionChangePending = true;
            return;
        }
        selectCycleStep = 0;
        setSelectionTracked(ic, selectCycleCaret, selectCycleCaret);
    }

    /** Long-press on the select key: the cursor keys start selecting instead of moving. */
    private void toggleSelectionMode() {
        InputConnection ic = getCurrentInputConnection();
        selectionMode = !selectionMode;
        selectCycleStep = 0;
        caseCycleStep = 0;
        if (selectionMode) {
            selectionAnchor = selStart;
            if (ic != null && selStart != selEnd) {
                setSelectionTracked(ic, selStart, selStart);
            }
        }
        updateEditingKeyTints();
    }

    /**
     * Case key: capitalized, then upper, then lower. Each step is derived from
     * the text as it was before the cycle started — capitalizing text that is
     * already uppercase would otherwise be a no-op. Does nothing without a
     * selection.
     */
    private void onCaseKey() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || selStart == selEnd) return;
        CharSequence selected = ic.getSelectedText(0);
        if (selected == null || selected.length() == 0) return;

        if (caseCycleStep == 0) caseCycleOriginal = selected.toString();
        caseCycleStep++;
        String out;
        if (caseCycleStep == 1) {
            out = capitalizeWords(caseCycleOriginal);
        } else if (caseCycleStep == 2) {
            out = caseCycleOriginal.toUpperCase(Locale.getDefault());
        } else {
            out = caseCycleOriginal.toLowerCase(Locale.getDefault());
            caseCycleStep = 0;
        }

        int start = Math.min(selStart, selEnd);
        // commitText replaces the selection and leaves the caret after it, so the
        // selection has to be restored for the next step of the cycle. Batched so
        // the field doesn't flicker in between.
        ic.beginBatchEdit();
        ic.commitText(out, 1);
        setSelectionTracked(ic, start, start + out.length());
        ic.endBatchEdit();
        updateEditingKeyTints();
    }

    private String capitalizeWords(String text) {
        StringBuilder out = new StringBuilder(text.toLowerCase(Locale.getDefault()));
        boolean atWordStart = true;
        for (int i = 0; i < out.length(); i++) {
            char c = out.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (atWordStart) out.setCharAt(i, Character.toUpperCase(c));
                atWordStart = false;
            } else {
                atWordStart = true;
            }
        }
        return out.toString();
    }

    private void commitCharacter(String character) {
        onTextEdited();
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(character, 1);
    }

    /**
     * Any edit leaves the remembered offsets pointing at text that has moved, and
     * ends the modes anchored to it — including selection mode, so a stray
     * cursor key can't start selecting long after the user forgot it was on.
     */
    private void onTextEdited() {
        lastDictationStart = -1;
        lastDictationEnd = -1;
        selectCycleStep = 0;
        caseCycleStep = 0;
        if (selectionMode) {
            selectionMode = false;
            updateEditingKeyTints();
        }
    }

    /**
     * Starts the editing keys over for a newly focused field: the cycles forget
     * what they were doing, and the caret mirror adopts the offsets the editor
     * reports for itself. Seeding it matters because onUpdateSelection is not
     * guaranteed to arrive before the first key press — a WebView or a custom
     * editor may never send one — and a key acting on the previous field's
     * offsets would move the caret somewhere the user never was.
     */
    private void resetEditingState(EditorInfo attribute) {
        selectionMode = false;
        selectCycleStep = 0;
        caseCycleStep = 0;
        caseCycleOriginal = null;
        lastDictationStart = -1;
        lastDictationEnd = -1;
        // EditorInfo reports -1 when it does not know where the caret is; the
        // start of the field is the one offset that is valid in every field.
        int start = attribute == null ? -1 : attribute.initialSelStart;
        int end = attribute == null ? -1 : attribute.initialSelEnd;
        if (start < 0 || end < 0) {
            start = 0;
            end = 0;
        }
        selStart = expectedSelStart = start;
        selEnd = expectedSelEnd = end;
        selectionChangePending = false;
        updateEditingKeyTints();
    }

    /** Accents the keys whose behaviour is currently modified. */
    private void updateEditingKeyTints() {
        if (!viewEditRow) return;
        tintKey(selectButton, selectionMode, true);
        tintKey(wordLeftButton, selectionMode, false);
        tintKey(charLeftButton, selectionMode, false);
        tintKey(charRightButton, selectionMode, false);
        tintKey(wordRightButton, selectionMode, false);
        tintKey(shiftButton, caseCycleStep > 0, false);
        // The bar under the arrow is the caps-lock convention, so it belongs to
        // the all-uppercase step alone.
        if (shiftButton instanceof android.widget.ImageView) {
            ((android.widget.ImageView) shiftButton).setImageResource(
                    caseCycleStep == 2 ? R.drawable.ic_shift_lock : R.drawable.ic_shift);
        }
    }

    /** Tonal key state: {@code strong} is the primary pair the record button uses. */
    private void tintKey(View key, boolean active, boolean strong) {
        if (key == null) return;
        int background;
        int foreground;
        if (!active) {
            background = com.google.android.material.R.attr.colorSurfaceContainerHighest;
            foreground = com.google.android.material.R.attr.colorOnSurfaceVariant;
        } else if (strong) {
            background = com.google.android.material.R.attr.colorPrimaryContainer;
            foreground = com.google.android.material.R.attr.colorOnPrimaryContainer;
        } else {
            background = com.google.android.material.R.attr.colorSecondaryContainer;
            foreground = com.google.android.material.R.attr.colorOnSecondaryContainer;
        }
        key.setBackgroundTintList(
                ColorStateList.valueOf(MaterialColors.getColor(key, background)));
        if (key instanceof android.widget.ImageView) {
            ((android.widget.ImageView) key)
                    .setColorFilter(MaterialColors.getColor(key, foreground));
        }
    }

    private boolean isPauseAudioEnabled() {
        return new File(getFilesDir(), "pause_audio").exists();
    }

    /**
     * The status-row keyboard key. Implied by the editing keys, which need the
     * bottom row for punctuation — so a marker pair left out of sync by an older
     * install still produces a usable keyboard.
     */
    private boolean isKbKeyTopEnabled() {
        return new File(getFilesDir(), "ime_kb_key_top").exists() || isEditRowEnabled();
    }

    private boolean isEditRowEnabled() {
        return new File(getFilesDir(), "ime_edit_row").exists();
    }

    /** "Record in background" is default ON; the marker file is the opt-out. */
    private boolean isStopOnHideEnabled() {
        return new File(getFilesDir(), "stop_on_hide").exists();
    }
}
