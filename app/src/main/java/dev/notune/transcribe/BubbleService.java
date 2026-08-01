package dev.notune.transcribe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Toast;

public class BubbleService extends Service {
    private static final String TAG = "BubbleService";
    public static final String ACTION_SHOW = "dev.notune.transcribe.BUBBLE_SHOW";
    public static final String ACTION_HIDE = "dev.notune.transcribe.BUBBLE_HIDE";
    public static final String ACTION_STOP_RECORDING = "dev.notune.transcribe.BUBBLE_STOP_REC";
    public static final String ACTION_REFRESH = "dev.notune.transcribe.BUBBLE_REFRESH";
    private static final String CHANNEL_ID = "BubbleChannel";
    private static final int NOTIFICATION_ID = 23456;

    static {
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("android_transcribe_app");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native libraries", e);
        }
    }

    private WindowManager mWindowManager;
    private View mBubbleView;
    private ImageView mBubbleIcon;
    private ProgressBar mBubbleProgress;
    private WindowManager.LayoutParams mParams;
    private Handler mMainHandler;
    private Runnable mUnloadRunnable;
    private boolean isRecording = false;
    private boolean isProcessing = false;
    private boolean isReloading = false;
    private boolean isModelUnloaded = false;

    @Override
    public void onCreate() {
        super.onCreate();
        mMainHandler = new Handler(Looper.getMainLooper());
        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        mUnloadRunnable = this::performIdleUnload;
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();

        if (ACTION_SHOW.equals(action)) {
            if (!Settings.canDrawOverlays(this)) {
                stopSelf();
                return START_NOT_STICKY;
            }
            Notification notification = createNotification();
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTIFICATION_ID, notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
                } else {
                    startForeground(NOTIFICATION_ID, notification);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to start foreground", e);
                stopSelf();
                return START_NOT_STICKY;
            }
            showBubble();
        } else if (ACTION_HIDE.equals(action)) {
            hideBubble();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        } else if (ACTION_STOP_RECORDING.equals(action)) {
            if (isRecording) {
                stopRecordingFlow();
            }
        } else if (ACTION_REFRESH.equals(action)) {
            // Settings (size / idle-unload interval) changed while visible.
            if (mBubbleView != null) {
                applyBubbleSize();
                scheduleIdleUnload();
            }
        }
        return START_NOT_STICKY;
    }

    private void showBubble() {
        if (mBubbleView != null) return;

        mBubbleView = LayoutInflater.from(this).inflate(R.layout.bubble_overlay, null);
        mBubbleIcon = mBubbleView.findViewById(R.id.bubble_icon);
        mBubbleProgress = mBubbleView.findViewById(R.id.bubble_progress);

        int layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

        mParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);

        mParams.gravity = Gravity.TOP | Gravity.START;
        int savedX = BubblePrefs.getX(this);
        int savedY = BubblePrefs.getY(this);
        if (savedX >= 0 && savedY >= 0) {
            mParams.x = savedX;
            mParams.y = savedY;
        } else {
            mParams.x = getResources().getDisplayMetrics().widthPixels - 160;
            mParams.y = getResources().getDisplayMetrics().heightPixels / 3;
        }

        mWindowManager.addView(mBubbleView, mParams);
        applyBubbleSize();
        updateBubbleState();
        makeDraggable();

        initNative(this);
        isModelUnloaded = false;
        scheduleIdleUnload();
    }

    private void makeDraggable() {
        final WindowManager.LayoutParams params = mParams;
        mBubbleView.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private int downParamX, downParamY;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        downParamX = params.x;
                        downParamY = params.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int dx = (int) (event.getRawX() - downX);
                        int dy = (int) (event.getRawY() - downY);
                        if (Math.abs(dx) > 8 || Math.abs(dy) > 8) moved = true;
                        params.x = downParamX + dx;
                        params.y = downParamY + dy;
                        if (mBubbleView != null) {
                            mWindowManager.updateViewLayout(mBubbleView, params);
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (moved) {
                            BubblePrefs.setX(BubbleService.this, params.x);
                            BubblePrefs.setY(BubbleService.this, params.y);
                        } else {
                            onBubbleTap();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private void onBubbleTap() {
        // Ignore taps while transcribing or reloading: keeps a rapid double
        // tap from starting two recordings or racing the async reload.
        if (isProcessing || isReloading) return;
        if (isRecording) {
            stopRecordingFlow();
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.bubble_need_mic, Toast.LENGTH_SHORT).show();
            return;
        }
        if (isModelUnloaded) {
            reloadThenRecord();
        } else {
            beginRecording();
        }
    }

    private void beginRecording() {
        cancelIdleUnload();
        isRecording = true;
        updateBubbleState();
        startRecordingNative();
    }

    /**
     * The model was unloaded to save memory/battery. Show a loading spinner,
     * reload asynchronously on a background thread (never blocking the UI), and
     * only start recording once the engine reports ready. Re-validates state and
     * permission before starting so a race can never begin a recording with no
     * session; on failure the bubble simply returns to its idle, still-unloaded
     * look and the overlay stays usable.
     */
    private void reloadThenRecord() {
        isReloading = true;
        isProcessing = true;
        cancelIdleUnload();
        updateBubbleState();
        new Thread(() -> {
            final boolean ok = ensureEngineNative();
            mMainHandler.post(() -> {
                isReloading = false;
                if (mBubbleView == null) {
                    isProcessing = false;
                    return;
                }
                if (!ok) {
                    isProcessing = false;
                    isModelUnloaded = true;
                    updateBubbleState();
                    Toast.makeText(this, R.string.bubble_load_failed, Toast.LENGTH_SHORT).show();
                    scheduleIdleUnload();
                    return;
                }
                isModelUnloaded = false;
                isProcessing = false;
                if (isRecording) {
                    updateBubbleState();
                    return;
                }
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    updateBubbleState();
                    Toast.makeText(this, R.string.bubble_need_mic, Toast.LENGTH_SHORT).show();
                    scheduleIdleUnload();
                    return;
                }
                beginRecording();
            });
        }).start();
    }

    private void stopRecordingFlow() {
        cancelIdleUnload();
        isRecording = false;
        isProcessing = true;
        updateBubbleState();
        stopRecordingNative();
    }

    // --- Battery-aware idle unload -----------------------------------------

    /**
     * Arms a single delayed unload callback for when the bubble sits idle. Any
     * previously armed callback is cancelled first, so there is exactly one
     * pending callback and no polling/wakeups. "Never" (0) arms nothing.
     */
    private void scheduleIdleUnload() {
        cancelIdleUnload();
        if (mBubbleView == null) return;
        if (isRecording || isProcessing || isReloading) return;
        int minutes = BubblePrefs.getUnloadMinutes(this);
        if (minutes <= 0) return;
        mMainHandler.postDelayed(mUnloadRunnable, minutes * 60_000L);
    }

    private void cancelIdleUnload() {
        if (mUnloadRunnable != null) mMainHandler.removeCallbacks(mUnloadRunnable);
    }

    /**
     * Fired by the delayed callback. Drops the heavy model only if the engine
     * is genuinely idle (no active transcription anywhere in this process —
     * see engine::unload_if_idle). The overlay and foreground service stay
     * visible and usable; the next tap reloads. If another engine user is
     * busy, a single retry is re-armed instead of polling.
     */
    private void performIdleUnload() {
        if (mBubbleView == null) return;
        if (isRecording || isProcessing || isReloading) return;
        new Thread(() -> {
            final boolean unloaded = unloadNative();
            mMainHandler.post(() -> {
                if (mBubbleView == null) return;
                if (isRecording || isProcessing || isReloading) return;
                if (unloaded) {
                    isModelUnloaded = true;
                    updateBubbleState();
                } else {
                    scheduleIdleUnload();
                }
            });
        }).start();
    }

    // --- Sizing ------------------------------------------------------------

    /**
     * Applies the persisted bubble diameter to an already-visible bubble:
     * resizes the overlay and scales its inner icon/spinner proportionally,
     * keeping at least a 48dp touch target, and nudges the position back on
     * screen if the resize would push it off.
     */
    private void applyBubbleSize() {
        if (mBubbleView == null || mParams == null) return;
        int dp = BubblePrefs.getSizeDp(this);
        float density = getResources().getDisplayMetrics().density;
        int sizePx = Math.round(dp * density);
        int innerPx = Math.round((dp / 2f) * density);

        mParams.width = sizePx;
        mParams.height = sizePx;
        setViewSize(mBubbleIcon, innerPx);
        setViewSize(mBubbleProgress, innerPx);

        int maxX = Math.max(0, getResources().getDisplayMetrics().widthPixels - sizePx);
        int maxY = Math.max(0, getResources().getDisplayMetrics().heightPixels - sizePx);
        mParams.x = Math.min(Math.max(0, mParams.x), maxX);
        mParams.y = Math.min(Math.max(0, mParams.y), maxY);

        mWindowManager.updateViewLayout(mBubbleView, mParams);
    }

    private void setViewSize(View v, int px) {
        if (v == null) return;
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        lp.width = px;
        lp.height = px;
        v.setLayoutParams(lp);
    }

    private void updateBubbleState() {
        mMainHandler.post(() -> {
            if (mBubbleIcon == null || mBubbleView == null) return;
            boolean loading = isProcessing || isReloading;
            if (isRecording) {
                mBubbleIcon.setVisibility(View.VISIBLE);
                mBubbleIcon.setImageResource(R.drawable.ic_stop);
                if (mBubbleProgress != null) mBubbleProgress.setVisibility(View.GONE);
                mBubbleView.setBackgroundResource(R.drawable.bg_bubble_recording);
                mBubbleView.setContentDescription(getString(R.string.bubble_recording));
            } else if (loading) {
                mBubbleIcon.setVisibility(View.GONE);
                if (mBubbleProgress != null) mBubbleProgress.setVisibility(View.VISIBLE);
                mBubbleView.setBackgroundResource(R.drawable.bg_bubble_processing);
                mBubbleView.setContentDescription(getString(
                        isReloading ? R.string.bubble_loading : R.string.bubble_processing));
            } else {
                mBubbleIcon.setVisibility(View.VISIBLE);
                mBubbleIcon.setImageResource(R.drawable.ic_mic);
                if (mBubbleProgress != null) mBubbleProgress.setVisibility(View.GONE);
                mBubbleView.setBackgroundResource(R.drawable.bg_bubble_idle);
                mBubbleView.setContentDescription(getString(R.string.bubble_idle));
            }
        });
    }

    private void hideBubble() {
        cancelIdleUnload();
        isRecording = false;
        isProcessing = false;
        isReloading = false;
        isModelUnloaded = false;
        if (mBubbleView != null && mWindowManager != null) {
            mWindowManager.removeView(mBubbleView);
            mBubbleView = null;
            mBubbleIcon = null;
            mBubbleProgress = null;
            mParams = null;
        }
        cleanupNative();
    }

    public void onStatusUpdate(String status) {
        mMainHandler.post(() -> {
            if (status != null && status.startsWith("Error")) {
                isRecording = false;
                if (isReloading) {
                    // reloadThenRecord() owns the state reset and error toast.
                    return;
                }
                isProcessing = false;
                updateBubbleState();
                Toast.makeText(this, status, Toast.LENGTH_SHORT).show();
                scheduleIdleUnload();
            }
        });
    }

    public void onAudioLevel(float level) {
    }

    public void onTextTranscribed(String text) {
        mMainHandler.post(() -> {
            isProcessing = false;
            updateBubbleState();
            scheduleIdleUnload();

            if (text == null || text.trim().isEmpty()) return;

            TranscriptionHistory.get(this).insert(text, TranscriptionHistory.SOURCE_BUBBLE);

            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Transcription", text));

            boolean inserted = InsertionAccessibilityService.tryInsert(this, text);
            if (inserted) {
                Toast.makeText(this, R.string.bubble_inserted, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.bubble_copied, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, getString(R.string.bubble_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private Notification createNotification() {
        Intent stopIntent = new Intent(this, BubbleService.class);
        stopIntent.setAction(ACTION_HIDE);
        PendingIntent stopPi = PendingIntent.getService(
                this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        Intent stopRecIntent = new Intent(this, BubbleService.class);
        stopRecIntent.setAction(ACTION_STOP_RECORDING);
        PendingIntent stopRecPi = PendingIntent.getService(
                this, 2, stopRecIntent, PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.bubble_notification_title))
                .setContentText(getString(R.string.bubble_notification_text))
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .addAction(new Notification.Action.Builder(
                        null, getString(R.string.bubble_stop_recording), stopRecPi).build())
                .addAction(new Notification.Action.Builder(
                        null, getString(R.string.bubble_hide), stopPi).build())
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        cancelIdleUnload();
        hideBubble();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private native void initNative(BubbleService service);
    private native void cleanupNative();
    private native void startRecordingNative();
    private native void stopRecordingNative();
    private native boolean unloadNative();
    private native boolean ensureEngineNative();
}
