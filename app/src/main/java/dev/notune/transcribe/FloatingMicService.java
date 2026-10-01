package dev.notune.transcribe;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

/**
 * Wispr-Flow-style floating mic: a draggable bubble drawn over every app.
 * Tap to start dictating, tap again to stop; the transcription is typed into
 * the focused field (see {@link DictationAccessibilityService}).
 */
public class FloatingMicService extends Service {
    private static final String TAG = "FloatingMicService";
    public static final String ACTION_START = "dev.notune.transcribe.START_FLOATING_MIC";
    public static final String ACTION_STOP = "dev.notune.transcribe.STOP_FLOATING_MIC";
    private static final String CHANNEL_ID = "FloatingMicChannel";
    private static final int NOTIFICATION_ID = 12346;
    private static final int BUBBLE_DP = 56;

    static {
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("android_transcribe_app");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native libraries", e);
        }
    }

    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private FrameLayout bubble;
    private ImageView micIcon;
    private Handler mainHandler;
    private boolean nativeReady = false;
    private boolean isRecording = false;
    private boolean isProcessing = false;

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, FloatingMicService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
    }

    public static void stop(Context ctx) {
        ctx.startService(new Intent(ctx, FloatingMicService.class).setAction(ACTION_STOP));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            FloatingMicPrefs.setEnabled(this, false);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!android.provider.Settings.canDrawOverlays(this)
                || checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return START_NOT_STICKY;
        }

        Notification n = createNotification();
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start foreground service", e);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (bubble == null) {
            initNative(this);
            nativeReady = true;
            addBubble();
        }
        return START_STICKY;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void addBubble() {
        float d = getResources().getDisplayMetrics().density;
        int size = (int) (BUBBLE_DP * d);

        bubble = new FrameLayout(this);
        bubble.setBackgroundResource(R.drawable.bg_floating_mic);
        bubble.setElevation(8 * d);
        bubble.setContentDescription(getString(R.string.floating_mic_cd));

        micIcon = new ImageView(this);
        micIcon.setImageResource(R.drawable.ic_mic);
        int pad = (int) (14 * d);
        micIcon.setPadding(pad, pad, pad, pad);
        bubble.addView(micIcon, new FrameLayout.LayoutParams(size, size));

        // NOT_FOCUSABLE keeps the focus (and the keyboard) in the app underneath.
        params = new WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        int[] saved = FloatingMicPrefs.getPosition(this);
        params.x = saved != null ? saved[0] : getResources().getDisplayMetrics().widthPixels - size;
        params.y = saved != null ? saved[1] : getResources().getDisplayMetrics().heightPixels / 2;

        final int touchSlop = (int) (8 * d);
        bubble.setOnTouchListener(new View.OnTouchListener() {
            private int startX, startY;
            private float downX, downY;
            private boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = params.x;
                        startY = params.y;
                        downX = e.getRawX();
                        downY = e.getRawY();
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (e.getRawX() - downX);
                        int dy = (int) (e.getRawY() - downY);
                        if (!dragging && Math.abs(dx) + Math.abs(dy) > touchSlop) dragging = true;
                        if (dragging) {
                            params.x = startX + dx;
                            params.y = startY + dy;
                            windowManager.updateViewLayout(bubble, params);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (dragging) {
                            FloatingMicPrefs.setPosition(FloatingMicService.this, params.x, params.y);
                        } else {
                            onBubbleTapped();
                        }
                        return true;
                }
                return false;
            }
        });

        windowManager.addView(bubble, params);
    }

    private void onBubbleTapped() {
        if (isProcessing) return;
        if (!isRecording) {
            isRecording = true;
            setBubbleState(true);
            startRecording(isAutoStopEnabled());
        } else {
            finishRecording();
        }
    }

    private void finishRecording() {
        if (!isRecording) return;
        isRecording = false;
        isProcessing = true;
        bubble.setAlpha(0.6f);
        stopRecording();
    }

    private void setBubbleState(boolean recording) {
        bubble.setScaleX(1f);
        bubble.setScaleY(1f);
        bubble.setAlpha(1f);
        micIcon.setColorFilter(recording ? 0xFFFF5252 : 0xFFFFFFFF);
    }

    private boolean isAutoStopEnabled() {
        return new java.io.File(getFilesDir(), "auto_stop").exists();
    }

    // --- Called from Rust ----------------------------------------------------

    public void onAutoStop() {
        mainHandler.post(this::finishRecording);
    }

    public void onStatusUpdate(String s) {
        if (s != null && s.startsWith("Error")) {
            mainHandler.post(() -> {
                isRecording = false;
                isProcessing = false;
                if (bubble != null) setBubbleState(false);
                Toast.makeText(this, s, Toast.LENGTH_LONG).show();
            });
        }
    }

    public void onAudioLevel(float level) {
        mainHandler.post(() -> {
            if (bubble != null && isRecording) {
                float scale = 1f + 0.35f * Math.min(1f, level);
                bubble.setScaleX(scale);
                bubble.setScaleY(scale);
            }
        });
    }

    public void onTextTranscribed(String text) {
        mainHandler.post(() -> {
            isProcessing = false;
            if (bubble != null) setBubbleState(false);
            if (text == null || text.trim().isEmpty()) return;
            if (!DictationAccessibilityService.insert(this, text)) {
                Toast.makeText(this, R.string.floating_mic_copied, Toast.LENGTH_LONG).show();
            }
        });
    }

    // --- Lifecycle -----------------------------------------------------------

    @Override
    public void onDestroy() {
        if (isRecording) {
            try { cancelRecording(); } catch (Throwable t) { /* ignore */ }
        }
        if (bubble != null) {
            try { windowManager.removeView(bubble); } catch (Exception ignored) { }
            bubble = null;
        }
        if (nativeReady) {
            try { cleanupNative(); } catch (Throwable t) { /* ignore */ }
            nativeReady = false;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.floating_mic_title), NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification createNotification() {
        Intent stop = new Intent(this, FloatingMicService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 0, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return b.setContentTitle(getString(R.string.floating_mic_title))
                .setContentText(getString(R.string.floating_mic_notif))
                .setSmallIcon(R.drawable.ic_mic)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.floating_mic_hide), stopPi).build())
                .build();
    }

    private native void initNative(FloatingMicService service);
    private native void cleanupNative();
    private native void startRecording(boolean autoStop);
    private native void stopRecording();
    private native void cancelRecording();
}
