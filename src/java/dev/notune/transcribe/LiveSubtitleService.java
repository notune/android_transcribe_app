package dev.notune.transcribe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.LinearLayout;

public class LiveSubtitleService extends Service {
    private static final String TAG = "LiveSubtitleService";
    public static final String ACTION_START = "dev.notune.transcribe.START_SUBTITLES";
    public static final String ACTION_STOP = "dev.notune.transcribe.STOP_SUBTITLES";
    private static final String CHANNEL_ID = "LiveSubtitlesChannel";
    private static final int NOTIFICATION_ID = 12345;

    static {
        try {
            System.loadLibrary("android_transcribe_app");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native library", e);
        }
    }

    private MediaProjectionManager mProjectionManager;
    private MediaProjection mMediaProjection;
    private AudioRecord mAudioRecord;
    private Thread mAudioThread;
    private boolean isRecording = false;

    private WindowManager mWindowManager;
    private View mOverlayView;
    private TextView mSubtitleText;
    private Handler mMainHandler;

    @Override
    public void onCreate() {
        super.onCreate();
        mMainHandler = new Handler(Looper.getMainLooper());
        mProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        if (ACTION_START.equals(intent.getAction())) {
            Notification notification = createNotification();
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
                } else {
                    startForeground(NOTIFICATION_ID, notification);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to start foreground service", e);
                stopSelf();
                return START_NOT_STICKY;
            }

            int code = intent.getIntExtra("code", 0);
            Intent data = intent.getParcelableExtra("data");
            
            Log.d(TAG, "Received start command. Code: " + code + ", Data: " + data);
            
            if (code != 0 && data != null) {
                startSubtitleSession(code, data);
            } else {
                Log.e(TAG, "Missing or invalid extras for media projection. Code: " + code + ", Data: " + data);
                stopSelf();
            }
        } else if (ACTION_STOP.equals(intent.getAction())) {
            stopSubtitleSession();
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private void startSubtitleSession(int code, Intent data) {
        if (isRecording) return;

        mMediaProjection = mProjectionManager.getMediaProjection(code, data);
        if (mMediaProjection == null) {
            stopSelf();
            return;
        }

        initNative(this);
        setupOverlay();
        startAudioCapture();
    }

    private void stopSubtitleSession() {
        isRecording = false;
        if (mAudioThread != null) {
            try {
                mAudioThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            mAudioThread = null;
        }
        if (mAudioRecord != null) {
            mAudioRecord.stop();
            mAudioRecord.release();
            mAudioRecord = null;
        }
        if (mMediaProjection != null) {
            mMediaProjection.stop();
            mMediaProjection = null;
        }
        removeOverlay();
        cleanupNative();
    }

    private View mSettingsPanel;
    private float currentInterval = 2.0f; // Default

    private void setupOverlay() {
        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        
        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(0xAA000000);
        rootLayout.setPadding(20, 20, 20, 20);

        // Header with Buttons
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.END);
        
        Button settingsBtn = new Button(this);
        settingsBtn.setText("⚙");
        settingsBtn.setTextSize(14);
        settingsBtn.setPadding(20, 0, 20, 0);
        settingsBtn.setBackgroundColor(0xFF555555);
        settingsBtn.setTextColor(0xFFFFFFFF);
        settingsBtn.setOnClickListener(v -> {
            if (mSettingsPanel.getVisibility() == View.GONE) {
                mSettingsPanel.setVisibility(View.VISIBLE);
            } else {
                mSettingsPanel.setVisibility(View.GONE);
            }
        });
        
        Button stopBtn = new Button(this);
        stopBtn.setText("Stop");
        stopBtn.setTextSize(12);
        stopBtn.setPadding(20, 0, 20, 0);
        stopBtn.setBackgroundColor(0xFFFF0000);
        stopBtn.setTextColor(0xFFFFFFFF);
        stopBtn.setOnClickListener(v -> {
            Intent stopIntent = new Intent(this, LiveSubtitleService.class);
            stopIntent.setAction(ACTION_STOP);
            startService(stopIntent);
        });
        
        header.addView(settingsBtn);
        // Add spacer
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(20, 1));
        header.addView(spacer);
        header.addView(stopBtn);
        rootLayout.addView(header);

        // Settings Panel (Hidden by default)
        mSettingsPanel = new LinearLayout(this);
        ((LinearLayout)mSettingsPanel).setOrientation(LinearLayout.VERTICAL);
        mSettingsPanel.setBackgroundColor(0xDD333333);
        mSettingsPanel.setPadding(10, 10, 10, 10);
        mSettingsPanel.setVisibility(View.GONE);
        
        TextView latencyLabel = new TextView(this);
        latencyLabel.setText("Update Interval: " + currentInterval + "s");
        latencyLabel.setTextColor(0xFFFFFFFF);
        ((LinearLayout)mSettingsPanel).addView(latencyLabel);
        
        android.widget.SeekBar latencyBar = new android.widget.SeekBar(this);
        latencyBar.setMax(40); // 1.0 to 5.0 -> 0 to 40 (+10 / 10)
        latencyBar.setProgress((int)((currentInterval - 1.0f) * 10));
        latencyBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                float val = 1.0f + (progress / 10.0f);
                latencyLabel.setText("Update Interval: " + val + "s");
                setUpdateInterval(val);
                currentInterval = val;
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });
        ((LinearLayout)mSettingsPanel).addView(latencyBar);
        
        TextView sizeLabel = new TextView(this);
        sizeLabel.setText("Text Size");
        sizeLabel.setTextColor(0xFFFFFFFF);
        ((LinearLayout)mSettingsPanel).addView(sizeLabel);
        
        android.widget.SeekBar sizeBar = new android.widget.SeekBar(this);
        sizeBar.setMax(20); // 12 to 32
        sizeBar.setProgress(6); // 18sp default
        sizeBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                if (mSubtitleText != null) {
                    mSubtitleText.setTextSize(12 + progress);
                }
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });
        ((LinearLayout)mSettingsPanel).addView(sizeBar);
        
        rootLayout.addView(mSettingsPanel);

        mSubtitleText = new TextView(this);
        mSubtitleText.setText("Waiting for audio...");
        mSubtitleText.setTextColor(0xFFFFFFFF);
        mSubtitleText.setTextSize(18);
        mSubtitleText.setGravity(Gravity.CENTER);
        rootLayout.addView(mSubtitleText);
        
        mOverlayView = rootLayout;

        int layoutFlag;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutFlag = WindowManager.LayoutParams.TYPE_PHONE;
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | 
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.BOTTOM;
        params.y = 100; // Margin bottom

        mWindowManager.addView(mOverlayView, params);
    }

    private void removeOverlay() {
        if (mOverlayView != null && mWindowManager != null) {
            mWindowManager.removeView(mOverlayView);
            mOverlayView = null;
        }
    }

    private void startAudioCapture() {
        Log.d(TAG, "Starting audio capture. MediaProjection: " + mMediaProjection);
        if (mMediaProjection == null) {
            Log.e(TAG, "MediaProjection is null, cannot capture audio");
            return;
        }

        AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(mMediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build();

        int sampleRate = 16000;
        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;

        int minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
        int bufferSize = Math.max(minBufferSize, 16000); // 1 second buffer roughly

        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(audioFormat)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build();

        try {
            mAudioRecord = new AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .setAudioPlaybackCaptureConfig(config)
                    .build();

            if (mAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize");
                stopSubtitleSession();
                return;
            }

            mAudioRecord.startRecording();
            if (mAudioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "AudioRecord failed to start recording. State: " + mAudioRecord.getRecordingState());
                stopSubtitleSession();
                return;
            }
            
            isRecording = true;
            Log.d(TAG, "AudioRecord started successfully");
            
            mAudioThread = new Thread(this::audioLoop);
            mAudioThread.start();
            
        } catch (Exception e) {
            Log.e(TAG, "Error starting AudioRecord", e);
            stopSubtitleSession();
        }
    }

    private void audioLoop() {
        Log.d(TAG, "Starting audio loop");
        int bufferSize = 1024; // Process in small chunks
        short[] buffer = new short[bufferSize];
        float[] floatBuffer = new float[bufferSize];
        int totalRead = 0;

        while (isRecording) {
            int read = mAudioRecord.read(buffer, 0, bufferSize);
            if (read > 0) {
                totalRead += read;
                if (totalRead % (16000 * 5) < read) { // Log approx every 5 seconds of audio
                    Log.d(TAG, "Reading audio... Total samples: " + totalRead);
                }
                
                // Convert short to float
                for (int i = 0; i < read; i++) {
                    floatBuffer[i] = buffer[i] / 32768.0f;
                }
                pushAudio(floatBuffer, read);
            } else {
                if (read == AudioRecord.ERROR_INVALID_OPERATION) {
                    Log.e(TAG, "Audio read error: INVALID_OPERATION");
                } else if (read == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, "Audio read error: BAD_VALUE");
                } else if (read == AudioRecord.ERROR_DEAD_OBJECT) {
                    Log.e(TAG, "Audio read error: DEAD_OBJECT");
                    isRecording = false;
                } else if (read == 0) {
                     // Sometimes happens if no audio is playing?
                } else {
                     Log.e(TAG, "Audio read error: " + read);
                }
            }
        }
        Log.d(TAG, "Audio loop finished");
    }

    // Called from Rust
    public void onSubtitleText(String text) {
        mMainHandler.post(() -> {
            if (mSubtitleText != null) {
                mSubtitleText.setText(text);
            }
        });
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Live Subtitles", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        createNotificationChannel();

        Intent stopIntent = new Intent(this, LiveSubtitleService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Live Subtitles Active")
                .setContentText("Recording internal audio...")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .addAction(new Notification.Action.Builder(null, "Stop", stopPendingIntent).build())
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // Native methods
    private native void initNative(LiveSubtitleService service);
    private native void cleanupNative();
    private native void pushAudio(float[] data, int length);
    private native void setUpdateInterval(float seconds);
}
