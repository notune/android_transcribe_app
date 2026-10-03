package dev.notune.transcribe;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Wispr Flow-style floating dictation bubble backed by this app's native
 * offline Parakeet engine. Appears when an editable text field has focus;
 * tap to start/stop recording; transcript is inserted into the focused
 * field via accessibility actions.
 */
public class BubbleService extends AccessibilityService {

    private static final int COL_IDLE = 0xE61E88E5;       // blue
    private static final int COL_LISTENING = 0xE6E53935;  // red
    private static final int COL_PROCESSING = 0xE6FB8C00; // orange
    private static final int COL_ERROR = 0xE6757575;      // grey

    private WindowManager wm;
    private TextView bubble;
    private boolean bubbleVisible;
    private boolean recording;
    private boolean modelReady;

    private AccessibilityNodeInfo focusedEditable;
    private String fieldSnapshot = "";
    private boolean userTypedSinceFocus;
    private int lastSelStart = -1, lastSelEnd = -1;
    private final java.util.HashMap<String, String> knownContent = new java.util.HashMap<>();
    private volatile long snoozedUntil;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // drag state
    private float dX, dY;
    private boolean moved, dragging;

    @Override
    protected void onServiceConnected() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();        info.eventTypes = AccessibilityEvent.TYPE_VIEW_FOCUSED
                | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        info.notificationTimeout = 100;
        setServiceInfo(info);
        System.loadLibrary("c++_shared");
        System.loadLibrary("android_transcribe_app");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Resume dictation after the mic permission was granted
        if (intent != null && BubbleResumeReceiver.ACTION.equals(intent.getAction())) {
            handler.postDelayed(this::startDictation, 400); // let the dialog close
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (recording || (bubbleVisible && bubble != null && dragging)) return;
        int t = event.getEventType();
        if (t == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
                && focusedEditable != null && event.getSource() != null
                && focusedEditable.equals(event.getSource())) {
            lastSelStart = event.getFromIndex();
            lastSelEnd = event.getToIndex();
            return;
        }
        if (t == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                && focusedEditable != null
                && event.getSource() != null
                && focusedEditable.equals(event.getSource())) {
            userTypedSinceFocus = true; // real keystrokes on this field
            return;
        }
        if (t == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            // Only react to a real focus event on the editable itself
            AccessibilityNodeInfo node = findFocusedEditable(root);
            if (node != null) {
                focusedEditable = node;
                userTypedSinceFocus = false;
                showBubble();
            } else {
                focusedEditable = null;
                if (bubbleVisible && !recording) hideBubble();
            }
        } else if (t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // New window: keep the bubble only if an editable is actually focused in it
            AccessibilityNodeInfo node = findFocusedEditable(getRootInActiveWindow());
            if (node != null) {
                focusedEditable = node;
                userTypedSinceFocus = false;
                showBubble();
            } else {
                focusedEditable = null;
                if (bubbleVisible && !recording) hideBubble();
            }
        }
    }

    /** Finds the editable node that currently HAS input focus (not just any editable in the tree). */
    private AccessibilityNodeInfo findFocusedEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable() && node.isFocused()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findFocusedEditable(node.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node) {        if (node == null) return null;
        if (node.isEditable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findEditable(node.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    private void showBubble() {
        if (bubbleVisible) return;
        if (snoozedUntil > System.currentTimeMillis()) {
            // Snoozed — reappear automatically after the delay.
            handler.postDelayed(this::checkReshow, snoozedUntil - System.currentTimeMillis() + 100);
            return;
        }
        bubbleVisible = true;
        dragging = false;
        bubble = new TextView(this);
        bubble.setText("🎤");
        bubble.setTextSize(26);
        bubble.setTextColor(0xFFFFFFFF);
        bubble.setGravity(Gravity.CENTER);
        bubble.setElevation(6f);
        setBubbleColor(COL_IDLE);
        final WindowManager.LayoutParams[] lpHolder = new WindowManager.LayoutParams[1];
        bubble.setOnTouchListener((v, ev) -> {
            WindowManager.LayoutParams lp = lpHolder[0];
            if (lp == null) return false;
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dX = lp.x - ev.getRawX();
                    dY = lp.y - ev.getRawY();
                    moved = false;
                    return false;
                case MotionEvent.ACTION_MOVE: {
                    float ndx = lp.x - ev.getRawX();
                    float ndy = lp.y - ev.getRawY();
                    if (Math.abs(ndx - dX) > 8 || Math.abs(ndy - dY) > 8) {
                        moved = true;
                        dragging = true;
                        showSnoozeZone();
                    }
                    if (moved) {
                        lp.x = (int) (ev.getRawX() + dX);
                        lp.y = (int) (ev.getRawY() + dY);
                        try { wm.updateViewLayout(bubble, lp); } catch (Exception ignored) {}
                        return true;
                    }
                    return false;
                }
                case MotionEvent.ACTION_UP: {
                    boolean wasDrag = moved;
                    moved = false;
                    dragging = false;
                    hideSnoozeZone();
                    // Drop ON the snooze zone (bottom banner) = snooze.
                    int h = getResources().getDisplayMetrics().heightPixels;
                    boolean droppedOnZone = wasDrag
                            && (lp.y + bubble.getHeight()) > h - snoozeZoneHeight();
                    if (droppedOnZone) { snoozeBubble(); return true; }
                    // Snap to nearest vertical edge (magnetic sticky sides).
                    if (wasDrag && lp != null) {
                        int w = getResources().getDisplayMetrics().widthPixels;
                        int center = lp.x + bubble.getWidth() / 2;
                        lp.x = (center < w / 2) ? (int) (8 * getResources().getDisplayMetrics().density)
                                : w - (int) (64 * getResources().getDisplayMetrics().density);
                        try { wm.updateViewLayout(bubble, lp); } catch (Exception ignored) {}
                    }
                    return wasDrag;
                }
            }
            return false;
        });
        bubble.setOnClickListener(v -> {
            if (recording) stopDictation();
            else startDictation();
        });
        // Long-press removed (too sensitive while dragging). Snooze = drag
        // the bubble to the bottom edge of the screen (drop zone).
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                (int) (56 * getResources().getDisplayMetrics().density),
                (int) (56 * getResources().getDisplayMetrics().density),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = (int) (getResources().getDisplayMetrics().widthPixels - 68 * getResources().getDisplayMetrics().density);
        lp.y = (int) (200 * getResources().getDisplayMetrics().density);
        lpHolder[0] = lp;
        wm.addView(bubble, lp);
    }

    private void setBubbleColor(int color) {
        if (bubble == null) return;
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setShape(GradientDrawable.OVAL);
        bubble.setBackground(d);
    }

    private void hideBubble() {
        if (bubbleVisible && bubble != null) {
            try { wm.removeView(bubble); } catch (Exception ignored) {}
            bubbleVisible = false;
            bubble = null;
        }
    }

    /** Snooze button: hide the bubble for one minute, then it comes back
     *  automatically next time an editable field is focused. */
    private void snoozeBubble() {
        snoozedUntil = System.currentTimeMillis() + 60_000;
        hideBubble();
        Toast.makeText(this, "Bubble snoozed for 1 min", Toast.LENGTH_SHORT).show();
    }

    // --- Visible snooze drop zone (bottom banner, shown while dragging) ---
    private TextView snoozeZone;

    private int snoozeZoneHeight() {
        return (int) (72 * getResources().getDisplayMetrics().density);
    }

    private void showSnoozeZone() {
        if (snoozeZone != null) return;
        snoozeZone = new TextView(this);
        snoozeZone.setText("✕  Drop here to snooze");
        snoozeZone.setTextSize(15);
        snoozeZone.setTextColor(0xFFFFFFFF);
        snoozeZone.setGravity(Gravity.CENTER);
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xCCD32F2F);
        d.setCornerRadius(20 * getResources().getDisplayMetrics().density);
        snoozeZone.setBackground(d);
        snoozeZone.setElevation(5f);
        WindowManager.LayoutParams zlp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                snoozeZoneHeight(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        zlp.gravity = Gravity.BOTTOM;
        try { wm.addView(snoozeZone, zlp); } catch (Exception ignored) {}
    }

    private void hideSnoozeZone() {
        if (snoozeZone != null) {
            try { wm.removeView(snoozeZone); } catch (Exception ignored) {}
            snoozeZone = null;
        }
    }

    private void checkReshow() {
        if (snoozedUntil > System.currentTimeMillis() || bubbleVisible || recording) return;
        AccessibilityNodeInfo node = findFocusedEditable(getRootInActiveWindow());
        if (node != null) {
            focusedEditable = node;
            userTypedSinceFocus = false;
            showBubble();
        }
    }

    private void startDictation() {
        focusedEditable = findFocusedEditable(getRootInActiveWindow());
        if (focusedEditable == null) {
            error("No text field focused");
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // Ask via an invisible activity (no app switch); dictation resumes
            // automatically once granted (BubbleResumeReceiver).
            Intent i = new Intent(this, BubblePermissionActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            Toast.makeText(this, "Grant the microphone to start dictating", Toast.LENGTH_SHORT).show();
            return;
        }
        recording = true;
        modelReady = false;
        // Snapshot the field content BEFORE recording. The placeholder/hint
        // is static — if the content is unchanged after dictation the field
        // was actually empty and we replace the whole content.
        fieldSnapshot = focusedEditable.getText() != null
                ? focusedEditable.getText().toString() : "";
        bubble.setText("…");
        setBubbleColor(COL_LISTENING);
        initNative(this);
        startRecording();
        Toast.makeText(this, "Recording — tap bubble to stop", Toast.LENGTH_SHORT).show();
    }

    private void stopDictation() {
        if (!recording) return;
        recording = false;
        bubble.setText("…");
        setBubbleColor(COL_PROCESSING);
        stopRecording();
    }

    private void cancelDictation() {
        try { cancelRecording(); } catch (Throwable ignored) {}
        recording = false;
        idle("🎤");
    }

    private void error(String msg) {
        recording = false;
        if (bubble != null) { bubble.setText("!"); setBubbleColor(COL_ERROR); }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        handler.postDelayed(() -> idle("🎤"), 2500);
    }

    private void idle(String icon) {
        recording = false;
        if (bubble != null) { bubble.setText(icon); setBubbleColor(COL_IDLE); }
    }

    // ---- Called from Rust ----

    public void onStatusUpdate(String s) {
        handler.post(() -> {
            if ("Ready".equals(s)) modelReady = true;
        });
    }

    public void onAudioLevel(float level) {
        // simple pulse feedback
        handler.post(() -> {
            if (bubble != null && recording) {
                float s = 1f + Math.min(0.35f, level * 0.8f);
                bubble.setScaleX(s);
                bubble.setScaleY(s);
            }
        });
    }

    public void onTextTranscribed(String text) {
        handler.post(() -> {
            try { cleanupNative(); } catch (Throwable ignored) {}
            if (text == null || text.trim().isEmpty()) {
                Toast.makeText(this, "Nothing recognized", Toast.LENGTH_SHORT).show();
                idle("🎤");
                return;
            }
            insertText(text.trim());
            idle("🎤");
        });
    }

    public void onAutoStop() {
        handler.post(this::stopDictation);
    }

    private void insertText(String text) {
        if (focusedEditable == null) {
            error("Text field lost focus");
            return;
        }
        // Preferred path: SET_TEXT (no system "pasted from clipboard" toast).
        // Gotcha: empty fields often return the HINT text (e.g. "Message"),
        // so if content == hint we replace it instead of appending.
        try {
            CharSequence cur = focusedEditable.getText();
            String now = (cur == null) ? "" : cur.toString();
            // Generic, app-agnostic placeholder handling:
            // Track whether ANY text-change event fired on this field since it
            // gained focus. If none did, the field content is whatever the app
            // pre-populated — for an empty field that's the placeholder/hint
            // ("Message", "Type a message…", "Signal message", any language).
            // Guard against nuking a long pre-existing draft: only strip when
            // the untouched content is short and hint-like (≤40 chars, ≤6 words,
            // or matching the node's exposed hint text).
            String base = now;
            // Remember the last REAL content we saw per field. If we already
            // recorded real user text (or our own earlier insert) for this
            // node, the current content is genuine — append, never strip.
            String key = String.valueOf(System.identityHashCode(focusedEditable));
            String known = knownContent.get(key);
            boolean isEmptyish = !userTypedSinceFocus
                    && now.equals(fieldSnapshot) && !now.isEmpty()
                    && (known == null || known.isEmpty());
            if (isEmptyish) {
                CharSequence hint = focusedEditable.getHintText();
                boolean hintMatch = hint != null && hint.toString().contentEquals(now);
                int words = now.trim().split("\\s+").length;
                boolean shortHintLike = now.length() <= 40 && words <= 6;
                if (hintMatch || shortHintLike) base = "";
            }
            knownContent.put(key, base + text);
            // Insert at the cursor position when available; append otherwise.
            // Cursor info is read synchronously; if the app doesn't expose it
            // (sel == -1), fall back to the last selection-changed event seen
            // for this field — generic, works app-wide.
            int sel = focusedEditable.getTextSelectionStart();
            int end = focusedEditable.getTextSelectionEnd();
            if (sel < 0 || end < sel) { sel = lastSelStart; end = lastSelEnd; }
            if (sel < 0 || end < sel) { sel = base.length(); end = base.length(); }
            String out;
            if (base.isEmpty()) {
                out = text;
            } else if (sel <= base.length() && end <= base.length()) {
                // If the whole text is selected, behave like typing: replace it.
                out = (sel == 0 && end == base.length())
                        ? text
                        : base.substring(0, sel) + text + base.substring(end);
            } else {
                out = base + text; // safety — out-of-range selection
            }
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    out);
            if (focusedEditable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                Toast.makeText(this, "Inserted", Toast.LENGTH_SHORT).show();
                return;
            }
            // Fallback: clipboard + paste (shows the system toast, but always works)
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("dictation", text));
            AccessibilityNodeInfo fresh = findFocusedEditable(getRootInActiveWindow());
            if (fresh == null) fresh = focusedEditable;
            fresh.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            if (fresh.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                Toast.makeText(this, "Inserted", Toast.LENGTH_SHORT).show();
                return;
            }
            Toast.makeText(this, "Text on your clipboard — long-press the field to paste", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            error("Insert failed: " + t.getMessage());
        }
    }

    // ---- Native ----
    private native void initNative(BubbleService service);
    private native void cleanupNative();
    private native void startRecording();
    private native void stopRecording();
    private native void cancelRecording();

    @Override public void onInterrupt() {}

    @Override
    public void onDestroy() {
        if (recording) { try { cancelRecording(); } catch (Throwable ignored) {} }
        try { cleanupNative(); } catch (Throwable ignored) {}
        hideBubble();
        super.onDestroy();
    }
}
