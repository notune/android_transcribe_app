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
import android.util.AtomicFile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.view.MotionEvent;
import android.view.inputmethod.EditorInfo;
import android.content.res.ColorStateList;
import android.view.ContextThemeWrapper;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;

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
    private PendingDictationDraft pendingDraft;
    private AtomicFile pendingDraftFile;
    private String recoveryReadError;
    private String recoveryMessage;
    private View recoveryPanel;
    private Button insertButton;
    private Button copyButton;
    private Button discardButton;

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        pendingDraftFile = new AtomicFile(new File(getNoBackupFilesDir(), "pending-dictation"));
        pendingDraft = readPendingDraft();
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
            recoveryPanel = view.findViewById(R.id.ime_recovery_panel);
            insertButton = view.findViewById(R.id.ime_recovery_insert);
            copyButton = view.findViewById(R.id.ime_recovery_copy);
            discardButton = view.findViewById(R.id.ime_recovery_discard);

            insertButton.setOnClickListener(v -> insertPendingDraft());
            copyButton.setOnClickListener(v -> copyPendingDraft());
            discardButton.setOnClickListener(v -> discardPendingDraft());

            switchKeyboardButton.setOnClickListener(v -> {
                if (isRecording) {
                    pendingSwitchBack = true;
                    stopRecording();
                    updateRecordButtonUI(false);
                } else {
                    switchToPreviousInputMethod();
                }
            });

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

            backspaceButton.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
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
            });

            spaceButton.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
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
                // Default: keep recording in the background. If the target is
                // unavailable at completion, the result becomes a saved draft.
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
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        inputActive = true;
        // Rebuild the keyboard if the theme preference changed while this
        // (long-lived) IME process stayed alive, so it matches the app setting.
        if (inputView != null
                && ThemePrefs.isNight(ThemePrefs.wrapForNight(this, ThemePrefs.getMode(this))) != viewIsNight) {
            setInputView(onCreateInputView());
        }
        renderRecovery();
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
            statusView.setText("Listening...");
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
        renderRecovery();
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
            pendingDraft = pendingDraft == null
                    ? new PendingDictationDraft(PendingDictationDraft.PENDING, committed)
                    : pendingDraft.stageCurrent(committed);
            boolean staged = writePendingDraft(pendingDraft);
            if (!staged) {
                recoveryMessage = "Could not save the latest dictation";
            } else if (inputActive && getCurrentInputConnection() != null) {
                recoveryMessage = pendingDraft.savedText.isEmpty()
                        ? null : "Older dictation stays saved";
                commitPendingDraft(false);
            }
            if (pauseAudioActive) {
                audioPauser.abandon(this);
                pauseAudioActive = false;
            }
            updateRecordButtonUI(false);
            if (statusView != null) statusView.setText("Tap to Record");
            updateUiState();
            if (pendingSwitchBack) {
                pendingSwitchBack = false;
                switchToPreviousInputMethod();
            }
        });
    }

    private void commitPendingDraft(boolean includeSaved) {
        if (pendingDraft == null) return;
        if (!includeSaved && (!pendingDraft.hasCurrent() || pendingDraft.text.isEmpty())) return;
        String textToCommit = includeSaved ? pendingDraft.recoveryText() : pendingDraft.text;
        if (textToCommit.isEmpty()) return;
        InputConnection ic = getCurrentInputConnection();
        if (!inputActive || ic == null) {
            recoveryMessage = "No text field is available";
            renderRecovery();
            return;
        }

        PendingDictationDraft original = pendingDraft;
        PendingDictationDraft attempted = includeSaved
                ? original.asSingleAttempt()
                : original.withState(PendingDictationDraft.ATTEMPTED);
        pendingDraft = attempted;
        if (!writePendingDraft(attempted)) {
            pendingDraft = original;
            recoveryMessage = "Could not save the insertion attempt. Text was not sent.";
            renderRecovery();
            return;
        }

        EditorSnapshot before = readEditorSnapshot(ic);
        boolean accepted;
        try {
            accepted = ic.commitText(attempted.text, 1);
        } catch (Throwable t) {
            Log.w(TAG, "editor commit failed", t);
            recoveryMessage = "Editor write failed; text may already be inserted";
            renderRecovery();
            return;
        }
        if (!accepted) {
            pendingDraft = original;
            writePendingDraft(original);
            recoveryMessage = "Editor rejected dictated text";
            renderRecovery();
            return;
        }

        EditorSnapshot after = readEditorSnapshot(ic);
        if (!EditorSnapshot.isConfirmedCommit(before, after, attempted.text)) {
            recoveryMessage = "Editor did not confirm the write; text may already be inserted";
            renderRecovery();
            return;
        }

        selectTranscriptionIfEnabled(ic, attempted.text, after);
        if (includeSaved) {
            if (clearPendingDraft()) recoveryMessage = null;
            return;
        }

        PendingDictationDraft remaining = attempted.withoutCurrent();
        if (remaining.isEmpty()) {
            if (clearPendingDraft()) recoveryMessage = null;
        } else {
            pendingDraft = remaining;
            if (writePendingDraft(remaining)) {
                recoveryMessage = "Older dictation stays saved";
                renderRecovery();
            } else {
                pendingDraft = attempted;
                recoveryMessage = "Could not clear the delivered copy; it may be shown again";
                renderRecovery();
            }
        }
    }

    private void selectTranscriptionIfEnabled(
            InputConnection ic, String committed, EditorSnapshot snapshot) {
        if (pendingSwitchBack || !new File(getFilesDir(), "select_transcription").exists()) return;
        if (snapshot == null || !snapshot.hasValidSelection()) return;
        int end = snapshot.globalSelectionStart();
        int start = end - committed.length();
        if (start >= 0) ic.setSelection(start, end);
    }

    static final class EditorSnapshot {
        final String text;
        final int startOffset;
        final int selectionStart;
        final int selectionEnd;

        EditorSnapshot(String text, int startOffset, int selectionStart, int selectionEnd) {
            this.text = text;
            this.startOffset = startOffset;
            this.selectionStart = selectionStart;
            this.selectionEnd = selectionEnd;
        }

        static boolean isConfirmedCommit(
                EditorSnapshot before, EditorSnapshot after, String inserted) {
            return before != null && after != null && after.isExactCommitOf(before, inserted);
        }

        boolean isExactCommitOf(EditorSnapshot before, String inserted) {
            if (!isComparableTo(before)) return false;
            int start = Math.min(before.selectionStart, before.selectionEnd);
            int end = Math.max(before.selectionStart, before.selectionEnd);
            String expected = before.text.substring(0, start) + inserted + before.text.substring(end);
            int cursor = start + inserted.length();
            return expected.equals(text)
                    && selectionStart == cursor && selectionEnd == cursor;
        }

        boolean isKnownMismatchFrom(EditorSnapshot before, String inserted) {
            return isComparableTo(before) && !isExactCommitOf(before, inserted);
        }

        boolean isComparableTo(EditorSnapshot before) {
            return before != null && startOffset == before.startOffset
                    && hasValidSelection() && before.hasValidSelection();
        }

        boolean hasValidSelection() {
            int start = Math.min(selectionStart, selectionEnd);
            int end = Math.max(selectionStart, selectionEnd);
            return start >= 0 && end <= text.length();
        }

        int globalSelectionStart() {
            return startOffset + selectionStart;
        }
    }

    private EditorSnapshot readEditorSnapshot(InputConnection ic) {
        try {
            android.view.inputmethod.ExtractedTextRequest request =
                    new android.view.inputmethod.ExtractedTextRequest();
            request.hintMaxChars = 1024 * 1024;
            android.view.inputmethod.ExtractedText extracted = ic.getExtractedText(request, 0);
            if (extracted == null || extracted.text == null) return null;
            return new EditorSnapshot(extracted.text.toString(), extracted.startOffset,
                    extracted.selectionStart, extracted.selectionEnd);
        } catch (Throwable t) {
            return null;
        }
    }

    private PendingDictationDraft readPendingDraft() {
        try {
            PendingDictationDraft restored = PendingDictationDraft.decode(pendingDraftFile.readFully());
            if (restored == null) {
                recoveryReadError = "Saved dictation is damaged. Discard it to remove it.";
                Log.e(TAG, "pending dictation record is malformed");
            }
            return restored;
        } catch (FileNotFoundException e) {
            if (!pendingDraftArtifactsExist()) return null;
            recoveryReadError = "Saved dictation could not be read. Discard it to remove it.";
            Log.e(TAG, "pending dictation artifacts cannot be opened", e);
            return null;
        } catch (Throwable t) {
            recoveryReadError = "Saved dictation could not be read. Discard it to remove it.";
            Log.e(TAG, "could not read pending dictation", t);
            return null;
        }
    }

    private boolean writePendingDraft(PendingDictationDraft draft) {
        FileOutputStream output = null;
        try {
            output = pendingDraftFile.startWrite();
            output.write(draft.encode());
            pendingDraftFile.finishWrite(output);
            output = null;
            PendingDictationDraft check = PendingDictationDraft.decode(pendingDraftFile.readFully());
            boolean matches = draft.sameAs(check);
            if (matches) recoveryReadError = null;
            return matches;
        } catch (Throwable t) {
            if (output != null) pendingDraftFile.failWrite(output);
            Log.e(TAG, "could not persist pending dictation", t);
            return false;
        }
    }

    private boolean clearPendingDraft() {
        try {
            pendingDraftFile.delete();
        } catch (Throwable t) {
            Log.e(TAG, "could not delete pending dictation", t);
        }
        if (pendingDraftArtifactsExist()) {
            recoveryMessage = "Could not clear saved dictation. It may already be inserted.";
            renderRecovery();
            return false;
        }
        pendingDraft = null;
        recoveryReadError = null;
        renderRecovery();
        return true;
    }

    private boolean pendingDraftArtifactsExist() {
        File base = pendingDraftFile.getBaseFile();
        return base.exists() || new File(base.getPath() + ".bak").exists()
                || new File(base.getPath() + ".new").exists();
    }

    private boolean hasUnresolvedDraft() {
        return pendingDraft != null || recoveryReadError != null;
    }

    private void renderRecovery() {
        if (recoveryPanel == null) return;
        if (!hasUnresolvedDraft()) {
            recoveryPanel.setVisibility(View.GONE);
            return;
        }
        recoveryPanel.setVisibility(View.VISIBLE);
        if (pendingDraft == null) {
            if (!isRecording && statusView != null) statusView.setText(recoveryReadError);
            insertButton.setVisibility(View.GONE);
            copyButton.setVisibility(View.GONE);
            discardButton.setVisibility(View.VISIBLE);
            return;
        }
        String label = recoveryMessage != null ? recoveryMessage
                : pendingDraft.mayAlreadyBeDelivered()
                ? "Delivery is uncertain; this text may already be in the field"
                : "Saved dictation";
        if (!isRecording && statusView != null) statusView.setText(label);
        insertButton.setText(pendingDraft.mayAlreadyBeDelivered()
                ? R.string.dictation_insert_again : R.string.dictation_insert);
        insertButton.setVisibility(isInsertButtonEnabled() ? View.VISIBLE : View.GONE);
        copyButton.setVisibility(View.VISIBLE);
        discardButton.setVisibility(View.VISIBLE);
    }

    private void insertPendingDraft() {
        commitPendingDraft(true);
        updateUiState();
    }

    private void copyPendingDraft() {
        if (pendingDraft == null) return;
        try {
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard == null) throw new IllegalStateException("clipboard unavailable");
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                    "dictation", pendingDraft.recoveryText()));
            recoveryMessage = "Dictation copied. Discard it when safe.";
        } catch (Throwable t) {
            recoveryMessage = "Could not copy dictation";
            Log.w(TAG, "clipboard write failed", t);
        }
        renderRecovery();
    }

    private void discardPendingDraft() {
        if (clearPendingDraft()) recoveryMessage = null;
        updateUiState();
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

    /** The recovery panel remains useful with Insert hidden: Copy and Discard stay available. */
    private boolean isInsertButtonEnabled() {
        return !new File(getFilesDir(), "no_insert_button").exists();
    }
}
