package dev.notune.transcribe;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/**
 * The bubble's overlay window. It is a Dialog window (not a bare
 * WindowManager view) because background blur — which blurs only the area
 * behind the window, clipped to the background's corner radius — is a
 * {@link Window} API (Window#setBackgroundBlurRadius, Android 12+).
 */
final class BubbleWindow {
    private final Dialog dialog;
    private final Window window;
    private int blurRadius;
    private int width = -1, height = -1;

    BubbleWindow(Context ctx, View content) {
        dialog = new Dialog(ctx, R.style.BubbleWindow);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setContentView(content);
        window = dialog.getWindow();
        window.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setGravity(Gravity.TOP | Gravity.START);
        window.setWindowAnimations(0);
    }

    void show(int x, int y, int w, int h) {
        setBounds(x, y, w, h);
        dialog.show();
    }

    boolean isShowing() {
        return dialog.isShowing();
    }

    void setBounds(int x, int y, int w, int h) {
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.x = x;
        lp.y = y;
        lp.width = w;
        lp.height = h;
        window.setAttributes(lp);
        if (w != width || h != height) {
            width = w;
            height = h;
            // The blur region takes its corner radius from the background.
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.TRANSPARENT);
            bg.setCornerRadius(Math.min(w, h) / 2f);
            window.setBackgroundDrawable(bg);
        }
    }

    /** 0 turns blur off. No-op before Android 12. */
    void setBlurRadius(int px) {
        if (px == blurRadius || Build.VERSION.SDK_INT < 31) return;
        blurRadius = px;
        window.setBackgroundBlurRadius(px);
    }

    void dismiss() {
        try {
            dialog.dismiss();
        } catch (Exception ignored) {
        }
    }
}
