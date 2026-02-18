package dev.notune.transcribe;

import android.content.Context;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;

public class AudioFocusPauser {
    private AudioFocusRequest focusRequest = null;
    private AudioManager.OnAudioFocusChangeListener listener = focusChange -> { };

    public void request(Context ctx) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;

            // always release first
            abandon(ctx);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest = new AudioFocusRequest.Builder(
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                ).build();
                am.requestAudioFocus(focusRequest);
            } else {
                // Pre-O: best effort
                am.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            }
        } catch (Exception ignored) { }
    }

    public void abandon(Context ctx) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (focusRequest != null) {
                    am.abandonAudioFocusRequest(focusRequest);
                }
                focusRequest = null;
            } else {
                am.abandonAudioFocus(listener);
            }
        } catch (Exception ignored) { }
    }
}
