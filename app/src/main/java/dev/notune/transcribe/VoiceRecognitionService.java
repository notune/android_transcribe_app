package dev.notune.transcribe;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
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
    /** The session in progress, or null. Touched on the main thread only. */
    private Session session;

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
        if (session != null) session.end();
        session = new Session(callback);

        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO not granted — open the app to grant it");
            session.onError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS);
            return;
        }

        if (!ModelStore.hasAnyModel(this)) {
            Log.w(TAG, "No speech model installed — open the app to add one");
            // "Supported, but not downloaded yet"; older callers only know
            // the generic server error.
            session.onError(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                    : SpeechRecognizer.ERROR_SERVER);
            return;
        }

        try {
            startListening(session);
        } catch (Throwable t) {
            Log.e(TAG, "startListening failed", t);
            session.onError(SpeechRecognizer.ERROR_CLIENT);
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
        if (session != null) {
            session.end();
            session = null;
        }
        try {
            cancelNative();
        } catch (Throwable t) {
            Log.e(TAG, "cancel failed", t);
        }
    }

    @Override
    public void onDestroy() {
        if (session != null) session.end();
        try {
            destroyNative();
        } catch (Throwable t) {
            Log.e(TAG, "destroyNative failed", t);
        }
        super.onDestroy();
    }

    /** Invoked by the shared engine loader during model warm-up; UI-less here. */
    public void onStatusUpdate(String status) {
        Log.d(TAG, "engine: " + status);
    }

    private interface Call {
        void run(Callback cb) throws RemoteException;
    }

    /**
     * One recognition session. Native code reports to it (from any thread);
     * it forwards to its own Callback on the main thread until the session
     * ends. Clients often cancel and restart while a transcription is still
     * running, and the late result of the cancelled session must not reach
     * the new one (it would replace what the user just said).
     */
    final class Session {
        private Callback callback;

        Session(Callback callback) {
            this.callback = callback;
        }

        /** Main thread only. */
        void end() {
            callback = null;
        }

        private void post(Call call, boolean last) {
            mainHandler.post(() -> {
                Callback cb = callback;
                if (cb == null) return;
                if (last) {
                    end();
                    if (session == this) session = null;
                }
                try {
                    call.run(cb);
                } catch (RemoteException ignored) {
                }
            });
        }

        // --- Called from native code (any thread) ---------------------------

        public void onReadyForSpeech() {
            post(cb -> cb.readyForSpeech(new Bundle()), false);
        }

        public void onBeginningOfSpeech() {
            post(Callback::beginningOfSpeech, false);
        }

        public void onRmsChanged(float rmsdB) {
            post(cb -> cb.rmsChanged(rmsdB), false);
        }

        public void onEndOfSpeech() {
            post(Callback::endOfSpeech, false);
        }

        public void onResults(String text) {
            post(cb -> {
                ArrayList<String> hypotheses = new ArrayList<>();
                hypotheses.add(text);
                Bundle bundle = new Bundle();
                bundle.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, hypotheses);
                // Some clients drop results without a score per hypothesis.
                bundle.putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, new float[]{1.0f});
                cb.results(bundle);
            }, true);
        }

        public void onError(int errorCode) {
            post(cb -> cb.error(errorCode), true);
        }

        /** Engine loading progress while finishing; UI-less here. */
        public void onStatusUpdate(String status) {
            Log.d(TAG, "engine: " + status);
        }
    }

    // --- Native methods (implemented in src/recog_service.rs) ----------------

    private native void initNative(VoiceRecognitionService service);
    private native void startListening(Session session);
    private native void stopListening();
    private native void cancelNative();
    private native void destroyNative();
}
