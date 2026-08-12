package dev.notune.transcribe;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;

/**
 * Exposes the offline transcriber as a system speech-to-text provider via
 * {@link android.speech.RecognitionService}. This is the API that keyboards
 * (Microsoft SwiftKey, Gboard, …) use through {@link SpeechRecognizer} to find
 * and drive an on-device recognizer.
 *
 * <p>Because the service is declared in the manifest, it is discoverable at all
 * times — even when the app process is not running or has been force-stopped by
 * the OS — so {@code SpeechRecognizer.isRecognitionAvailable()} stays true and
 * keyboards no longer report that "Google Speech Services aren't installed".
 *
 * <p>The heavy lifting (capture, silence endpointing, model inference) happens in
 * native code ({@code src/recog_service.rs}); this class only bridges the
 * {@link RecognitionService.Callback} to it.
 */
public class VoiceRecognitionService extends RecognitionService {

    private static final String TAG = "OfflineVoiceInput";

    static {
        try {
            NativeLibraries.load();
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native libraries", e);
        }
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Callback mCallback;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            initNative(this);
        } catch (Throwable t) {
            Log.e(TAG, "initNative failed", t);
        }
    }

    @Override
    protected void onStartListening(Intent recognizerIntent, Callback callback) {
        mCallback = callback;

        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO not granted — open the app to grant it");
            safeError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS);
            return;
        }

        try {
            startListening(this);
        } catch (Throwable t) {
            Log.e(TAG, "startListening failed", t);
            safeError(SpeechRecognizer.ERROR_CLIENT);
        }
    }

    @Override
    protected void onStopListening(Callback callback) {
        try {
            stopListening();
        } catch (Throwable t) {
            Log.e(TAG, "stopListening failed", t);
        }
    }

    @Override
    protected void onCancel(Callback callback) {
        try {
            cancelNative();
        } catch (Throwable t) {
            Log.e(TAG, "cancel failed", t);
        }
    }

    @Override
    public void onDestroy() {
        try {
            destroyNative();
        } catch (Throwable t) {
            Log.e(TAG, "destroyNative failed", t);
        }
        super.onDestroy();
    }

    // --- Callbacks invoked from native code (any thread) ---------------------

    public void onReadyForSpeech() {
        mainHandler.post(() -> {
            Callback cb = mCallback;
            if (cb == null) return;
            try { cb.readyForSpeech(new Bundle()); } catch (RemoteException ignored) {}
        });
    }

    public void onBeginningOfSpeech() {
        mainHandler.post(() -> {
            Callback cb = mCallback;
            if (cb == null) return;
            try { cb.beginningOfSpeech(); } catch (RemoteException ignored) {}
        });
    }

    public void onRmsChanged(float rmsdB) {
        mainHandler.post(() -> {
            Callback cb = mCallback;
            if (cb == null) return;
            try { cb.rmsChanged(rmsdB); } catch (RemoteException ignored) {}
        });
    }

    public void onEndOfSpeech() {
        mainHandler.post(() -> {
            Callback cb = mCallback;
            if (cb == null) return;
            try { cb.endOfSpeech(); } catch (RemoteException ignored) {}
        });
    }

    public void onResults(String text) {
        mainHandler.post(() -> {
            Callback cb = mCallback;
            if (cb == null) return;
            ArrayList<String> hypotheses = new ArrayList<>();
            hypotheses.add(text);
            Bundle bundle = new Bundle();
            bundle.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, hypotheses);
            try { cb.results(bundle); } catch (RemoteException ignored) {}
            mCallback = null;
        });
    }

    public void onError(int errorCode) {
        mainHandler.post(() -> {
            Callback cb = mCallback;
            if (cb == null) return;
            try { cb.error(errorCode); } catch (RemoteException ignored) {}
            mCallback = null;
        });
    }

    /** Invoked by the shared engine loader during model warm-up; UI-less here. */
    public void onStatusUpdate(String status) {
        Log.d(TAG, "engine: " + status);
    }

    private void safeError(int errorCode) {
        Callback cb = mCallback;
        if (cb == null) return;
        try { cb.error(errorCode); } catch (RemoteException ignored) {}
        mCallback = null;
    }

    // --- Native methods (implemented in src/recog_service.rs) ----------------

    private native void initNative(VoiceRecognitionService service);
    private native void startListening(VoiceRecognitionService service);
    private native void stopListening();
    private native void cancelNative();
    private native void destroyNative();
}
