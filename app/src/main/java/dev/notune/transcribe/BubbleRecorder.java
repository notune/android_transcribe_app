package dev.notune.transcribe;

import android.content.Context;
import android.content.ContextWrapper;
import android.util.Log;

/**
 * Native entry points for the Flow bubble's voice session (see bubble.rs).
 * The session target passed to {@link #initNative} receives the callbacks
 * onStatusUpdate(String), onAudioLevel(float) and onTextTranscribed(String);
 * it must be a Context, because the engine loads the model through it.
 */
final class BubbleRecorder {
    private static final String TAG = "FlowBubble";

    static {
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("android_transcribe_app");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native libraries", e);
        }
    }

    /**
     * Receives engine status during a warm-up load. It only logs, so a load
     * error never reaches the controller as a recording error.
     */
    static final class LoadSink extends ContextWrapper {
        LoadSink(Context base) {
            super(base);
        }

        @SuppressWarnings("unused") // called from native code
        public void onStatusUpdate(String status) {
            Log.i(TAG, "load: " + status);
        }
    }

    native void initNative(Context target);
    native void cleanupNative();
    native void startRecordingNative();
    native void stopRecordingNative();
    native void cancelRecordingNative();
    native boolean isEngineLoadedNative();
    native boolean unloadNative();
    /** Blocks until the model is loaded or fails. Call off the main thread. */
    native boolean loadEngineNative(Context sink);
}
