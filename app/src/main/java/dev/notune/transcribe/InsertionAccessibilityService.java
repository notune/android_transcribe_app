package dev.notune.transcribe;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

public class InsertionAccessibilityService extends AccessibilityService {
    private static final String TAG = "InsertionA11y";

    private static volatile InsertionAccessibilityService sInstance;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (sInstance == this) sInstance = null;
    }

    public static boolean tryInsert(Context ctx, String text) {
        if (!BubblePrefs.hasA11yConsent(ctx)) return false;
        InsertionAccessibilityService svc = sInstance;
        if (svc == null) return false;

        try {
            AccessibilityNodeInfo root = svc.getRootInActiveWindow();
            if (root == null) return false;

            AccessibilityNodeInfo focused = root.findFocus(
                    AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused == null) {
                root.recycle();
                return false;
            }

            if (!isSafeEditable(focused)) {
                focused.recycle();
                root.recycle();
                return false;
            }

            // BubbleService places the transcription on the clipboard first.
            // ACTION_PASTE inserts at the app-managed cursor without reading,
            // retaining, or reconstructing any existing field content.
            boolean ok = focused.performAction(AccessibilityNodeInfo.ACTION_PASTE);

            focused.recycle();
            root.recycle();
            return ok;
        } catch (Exception e) {
            Log.w(TAG, "Insertion failed", e);
            return false;
        }
    }

    private static boolean isSafeEditable(AccessibilityNodeInfo node) {
        if (!node.isEditable()) return false;
        if (!node.isVisibleToUser()) return false;

        CharSequence cls = node.getClassName();
        if (cls != null) {
            String s = cls.toString();
            if (s.contains("password") || s.contains("Password")) return false;
        }

        if (node.isPassword()) return false;

        int inputType = 0;
        try {
            Bundle extras = node.getExtras();
            if (extras != null) {
                inputType = extras.getInt("android.view.inputmethod.EditorInfo.inputType", 0);
            }
        } catch (Exception ignored) {
        }
        int variation = inputType & 0x000000f0;
        if (variation == 0x00000080) return false; // TYPE_TEXT_VARIATION_PASSWORD
        if (variation == 0x00000010) return false; // TYPE_NUMBER_VARIATION_PASSWORD
        if (variation == 0x00000090) return false; // TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        if (variation == 0x000000e0) return false; // TYPE_TEXT_VARIATION_WEB_PASSWORD

        return true;
    }

    public static void openSettings(Context ctx) {
        ctx.startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
