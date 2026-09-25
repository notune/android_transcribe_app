package dev.notune.transcribe;

import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Which window-blur path this device has:
 * <ul>
 *   <li>AOSP cross-window blur (Android 12+, {@code Window#setBackgroundBlurRadius}),
 *       when the device supports and enables it;</li>
 *   <li>Samsung One UI's own view blur ({@code android.view.SemBlurInfo} +
 *       {@code View#semSetBlurInfo}), reached by reflection. Samsung turns AOSP
 *       cross-window blur off, so this is the only real blur on Galaxy phones.</li>
 * </ul>
 */
final class BlurSupport {
    private static final String TAG = "FlowBubble";

    private static boolean sProbed;
    private static Constructor<?> sBuilderCtor;
    private static Method sSetRadius, sSetColor, sSetCorner, sBuild, sSetBlurInfo;
    private static int sModeWindow;

    private BlurSupport() {}

    static boolean crossWindow(WindowManager wm) {
        return Build.VERSION.SDK_INT >= 31 && wm != null && wm.isCrossWindowBlurEnabled();
    }

    static boolean samsung() {
        probe();
        return sSetBlurInfo != null;
    }

    static boolean any(WindowManager wm) {
        return crossWindow(wm) || samsung();
    }

    private static synchronized void probe() {
        if (sProbed) return;
        sProbed = true;
        try {
            Class<?> info = Class.forName("android.view.SemBlurInfo");
            Class<?> builder = Class.forName("android.view.SemBlurInfo$Builder");
            sModeWindow = info.getField("BLUR_MODE_WINDOW").getInt(null);
            sBuilderCtor = builder.getConstructor(int.class);
            sSetRadius = builder.getMethod("setRadius", int.class);
            sSetColor = builder.getMethod("setBackgroundColor", int.class);
            sSetCorner = builder.getMethod("setBackgroundCornerRadius", float.class);
            sBuild = builder.getMethod("build");
            sSetBlurInfo = View.class.getMethod("semSetBlurInfo", info);
            Log.i(TAG, "Samsung blur available");
        } catch (Throwable t) {
            sSetBlurInfo = null;
        }
    }

    /**
     * Blurs what is behind {@code view} with Samsung's API and paints
     * {@code tint} over it, clipped to {@code cornerPx}. Returns false when
     * the API is missing or refused, so the caller can fall back.
     */
    static boolean applySamsung(View view, int radiusPx, int tint, float cornerPx) {
        if (!samsung()) return false;
        try {
            Object b = sBuilderCtor.newInstance(sModeWindow);
            sSetRadius.invoke(b, radiusPx);
            sSetColor.invoke(b, tint);
            sSetCorner.invoke(b, cornerPx);
            sSetBlurInfo.invoke(view, sBuild.invoke(b));
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Samsung blur failed", t);
            return false;
        }
    }

    static void clearSamsung(View view) {
        if (!samsung()) return;
        try {
            sSetBlurInfo.invoke(view, (Object) null);
        } catch (Throwable ignored) {
        }
    }
}
