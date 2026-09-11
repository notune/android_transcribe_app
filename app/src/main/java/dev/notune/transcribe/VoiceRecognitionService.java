package dev.notune.transcribe;

import android.content.ContextParams;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
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
    /** Rate the model runs at, and the assumed rate when a caller sends audio without saying. */
    private static final int SAMPLE_RATE_HZ = 16000;

    static {
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("android_transcribe_app");
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

        ParcelFileDescriptor callerAudio = callerAudioSource(recognizerIntent);
        if (callerAudio != null) {
            startListeningFromCallerAudio(recognizerIntent, callback, callerAudio);
            return;
        }

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

    /**
     * Transcribes audio the caller captured itself, read from
     * {@link RecognizerIntent#EXTRA_AUDIO_SOURCE}, instead of opening the microphone here.
     *
     * <p>This is the only path by which an ordinary app can drive this recognizer on a device where
     * it is not the system-selected one. {@code SpeechRecognitionManagerServiceImpl.checkPrivilege}
     * grants {@code BIND_INCLUDE_CAPABILITIES} only to the component named in
     * {@code Settings.Secure.VOICE_RECOGNITION_SERVICE}, the configured on-device recognizer, or a
     * preinstalled one. Every other recognizer is bound with plain {@code BIND_AUTO_CREATE} and so
     * never holds the while-in-use microphone capability that a "only while using the app"
     * RECORD_AUDIO grant requires, which makes the RECORD_AUDIO proxy note in
     * {@code RecognitionService.dispatchStartListening} fail and the caller receive
     * {@code ERROR_INSUFFICIENT_PERMISSIONS}. Since Android 17 removed the recognizer picker from
     * Settings, most users cannot select this app at all, so that describes every third-party
     * caller. Taking the audio from the caller sidesteps it: the caller already holds the
     * microphone it is recording with, and this service opens no microphone of its own.
     *
     * <p>The caller owns the endpoint on this path: the utterance ends when it closes the
     * descriptor or calls {@code stopListening}, never on a pause. Trailing-silence endpointing is
     * a microphone-mode heuristic, and applying it to supplied audio would truncate any recording
     * that contains one.
     */
    private void startListeningFromCallerAudio(
            Intent recognizerIntent, Callback callback, ParcelFileDescriptor callerAudio) {
        attributeSessionToCaller(callback);

        int fd;
        try {
            fd = callerAudio.detachFd();
        } catch (Throwable t) {
            Log.e(TAG, "audio source descriptor unusable", t);
            safeError(SpeechRecognizer.ERROR_AUDIO);
            return;
        }

        try {
            startListeningFromAudioSource(
                    this,
                    fd,
                    recognizerIntent.getIntExtra(
                            RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE_HZ),
                    recognizerIntent.getIntExtra(
                            RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1),
                    recognizerIntent.getIntExtra(
                            RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,
                            AudioFormat.ENCODING_PCM_16BIT));
        } catch (Throwable t) {
            Log.e(TAG, "startListeningFromAudioSource failed", t);
            safeError(SpeechRecognizer.ERROR_CLIENT);
        }
    }

    @SuppressWarnings("deprecation")
    private ParcelFileDescriptor callerAudioSource(Intent recognizerIntent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return recognizerIntent.getParcelableExtra(
                        RecognizerIntent.EXTRA_AUDIO_SOURCE, ParcelFileDescriptor.class);
            }
            return recognizerIntent.getParcelableExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE);
        } catch (Throwable t) {
            Log.w(TAG, "audio source extra unreadable", t);
            return null;
        }
    }

    /**
     * Declares that this session's sensitive data is attributed to the caller.
     *
     * <p>{@code RecognitionService} treats the creation of a caller attribution context as the
     * service taking over blame, and skips its own RECORD_AUDIO proxy note for the session. That
     * note is what fails for a recognizer the platform has not selected, and on this path there is
     * nothing left for it to guard: the caller opened the microphone, and the audio arrives as a
     * descriptor it already owns.
     */
    private void attributeSessionToCaller(Callback callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        try {
            createContext(
                    new ContextParams.Builder()
                            .setNextAttributionSource(callback.getCallingAttributionSource())
                            .build());
        } catch (Throwable t) {
            Log.w(TAG, "caller attribution refused", t);
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
    private native void startListeningFromAudioSource(
            VoiceRecognitionService service,
            int fd,
            int sampleRate,
            int channelCount,
            int encoding);
    private native void stopListening();
    private native void cancelNative();
    private native void destroyNative();
}
