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
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Wispr-Flow-style floating mic: a draggable bubble drawn over every app.
 * Tap to start dictating, tap again to stop; the transcription is typed into
 * the focused field (see {@link DictationAccessibilityService}).
 */
public class FloatingMicService extends Service {
    private static final String TAG = "FloatingMicService";
    public static final String ACTION_START = "dev.notune.transcribe.START_FLOATING_MIC";
    public static final String ACTION_REFRESH = "dev.notune.transcribe.REFRESH_FLOATING_MIC";
    public static final String ACTION_STOP = "dev.notune.transcribe.STOP_FLOATING_MIC";
    private static final String CHANNEL_ID = "FloatingMicChannel";
    private static final int NOTIFICATION_ID = 12346;

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
    private FrameLayout stopZone;
    private ImageView stopZoneIcon;
    private TextView stopZoneLabel;
    private LinearLayout stopZoneIdle;
    private LinearLayout stopZoneContent;
    // Big-zone mode: the small bubble opened the zone over the keyboard, which then
    // starts/stops the recording until the bubble is tapped again.
    private boolean zoneMode = false;
    private WindowManager.LayoutParams stopZoneParams;
    private ImageView micIcon;
    private Handler mainHandler;
    private boolean nativeReady = false;
    private boolean isRecording = false;
    // Whether the current recording writes into the field while speaking.
    private boolean liveTyping = false;
    private final AudioFocusPauser audioPauser = new AudioFocusPauser();
    private boolean isProcessing = false;
    private float opacity = 1f;
    private float density = 1f;
    private DisplayManager displayManager;
    private final Runnable repositionRunnable = this::repositionForScreen;
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) { }
        @Override public void onDisplayRemoved(int displayId) { }
        @Override public void onDisplayChanged(int displayId) {
            // Fold/unfold and rotation: wait for the new size to settle, then re-place the bubble.
            mainHandler.removeCallbacks(repositionRunnable);
            mainHandler.postDelayed(repositionRunnable, 200);
        }
    };

    public static void start(Context ctx) {
        start(ctx, ACTION_START);
    }

    /** Re-applies size/opacity/side settings to the running bubble. */
    public static void refresh(Context ctx) {
        if (!FloatingMicPrefs.isEnabled(ctx)) return;
        start(ctx, ACTION_REFRESH);
    }

    /**
     * Starts the bubble if the user left it enabled, from a context where the app may be in the
     * background (after an update or boot). Android can refuse that; then it starts next time
     * the app or the accessibility service comes up.
     */
    public static void startIfPossible(Context ctx) {
        if (!FloatingMicPrefs.isEnabled(ctx)) return;
        try {
            start(ctx, ACTION_START);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not start the floating mic from the background", e);
        }
    }

    private static void start(Context ctx, String action) {
        Intent i = new Intent(ctx, FloatingMicService.class).setAction(action);
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
        displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        density = getResources().getDisplayMetrics().density;
        createNotificationChannel();
        DictationAccessibilityService.setKeyboardListener(visible -> mainHandler.post(this::updateVisibility));
    }

    /** Only shown while the keyboard is open (or busy); always shown if the accessibility service is off. */
    private void updateVisibility() {
        if (bubble == null) return;
        // Keyboard gone and nothing running: the big-zone mode ends with it.
        if (zoneMode && !isRecording && !isProcessing
                && DictationAccessibilityService.isRunning()
                && !DictationAccessibilityService.isKeyboardVisible()) {
            zoneMode = false;
        }
        updateStopZone();
        boolean show = !DictationAccessibilityService.isRunning()
                || DictationAccessibilityService.isKeyboardVisible()
                || isRecording || isProcessing;
        bubble.setVisibility(show ? View.VISIBLE : View.GONE);
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

        if (bubble != null && intent != null && ACTION_REFRESH.equals(intent.getAction())) {
            applySettings();
            return START_STICKY;
        }

        if (bubble == null) {
            initNative(this);
            nativeReady = true;
            addBubble();
        }
        return START_STICKY;
    }

    /** Real size in pixels of the screen currently in use (changes when a foldable folds or rotates). */
    private int[] screenSize() {
        Display display = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        DisplayMetrics m = new DisplayMetrics();
        display.getRealMetrics(m);
        return new int[]{m.widthPixels, m.heightPixels};
    }

    /** Positions are remembered per screen size, so each fold/rotation layout keeps its own spot. */
    private String screenKey() {
        int[] s = screenSize();
        return s[0] + "x" + s[1];
    }

    private int bubbleSizePx() {
        return (int) (FloatingMicPrefs.getSizeDp(this) * density);
    }

    private void placeFromPrefs() {
        int[] s = screenSize();
        int maxX = Math.max(0, s[0] - params.width);
        int maxY = Math.max(0, s[1] - params.height);
        float[] saved = FloatingMicPrefs.getPosition(this, screenKey());
        float xf = saved != null ? saved[0] : 1f;
        float yf = saved != null ? saved[1] : 0.5f;
        int side = FloatingMicPrefs.getSide(this);
        if (side == FloatingMicPrefs.SIDE_LEFT) xf = 0f;
        else if (side == FloatingMicPrefs.SIDE_RIGHT) xf = 1f;
        params.x = Math.round(Math.max(0f, Math.min(1f, xf)) * maxX);
        params.y = Math.round(Math.max(0f, Math.min(1f, yf)) * maxY);
    }

    private void savePosition() {
        int[] s = screenSize();
        int maxX = Math.max(0, s[0] - params.width);
        int maxY = Math.max(0, s[1] - params.height);
        float xf = maxX > 0 ? (float) params.x / maxX : 1f;
        float yf = maxY > 0 ? (float) params.y / maxY : 0.5f;
        FloatingMicPrefs.setPosition(this, screenKey(), xf, yf);
    }

    private void repositionForScreen() {
        if (bubble == null) return;
        placeFromPrefs();
        windowManager.updateViewLayout(bubble, params);
    }

    /** Applies size, opacity and side from the settings to the live bubble. */
    private void applySettings() {
        if (bubble == null) return;
        int size = bubbleSizePx();
        opacity = FloatingMicPrefs.getOpacity(this) / 100f;
        params.width = size;
        params.height = size;
        int pad = size / 4;
        micIcon.setPadding(pad, pad, pad, pad);
        micIcon.setLayoutParams(new FrameLayout.LayoutParams(size, size));
        bubble.setAlpha(isProcessing ? opacity * 0.6f : opacity);
        placeFromPrefs();
        windowManager.updateViewLayout(bubble, params);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void addBubble() {
        float d = density;
        int size = bubbleSizePx();
        opacity = FloatingMicPrefs.getOpacity(this) / 100f;

        bubble = new FrameLayout(this);
        bubble.setBackgroundResource(R.drawable.bg_floating_mic);
        bubble.setElevation(2 * d);
        bubble.setContentDescription(getString(R.string.floating_mic_cd));

        micIcon = new ImageView(this);
        micIcon.setImageResource(R.drawable.ic_mic);
        micIcon.setColorFilter(0xB3E8DEF8);
        int pad = size / 4;
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
        placeFromPrefs();

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
                            int[] s = screenSize();
                            boolean fixedSide = FloatingMicPrefs.getSide(FloatingMicService.this)
                                    != FloatingMicPrefs.SIDE_FREE;
                            if (!fixedSide) {
                                params.x = Math.max(0, Math.min(s[0] - params.width, startX + dx));
                            }
                            params.y = Math.max(0, Math.min(s[1] - params.height, startY + dy));
                            windowManager.updateViewLayout(bubble, params);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (dragging) {
                            savePosition();
                        } else {
                            onBubbleTapped();
                        }
                        return true;
                }
                return false;
            }
        });

        windowManager.addView(bubble, params);
        bubble.setAlpha(opacity);
        displayManager.registerDisplayListener(displayListener, mainHandler);
        updateVisibility();
    }

    private void onBubbleTapped() {
        if (isProcessing) return;
        if (zoneMode) {
            // Second tap on the bubble: leave big-zone mode (stopping a running recording).
            if (isRecording) finishRecording();
            closeZoneMode();
            return;
        }
        if (!isRecording) {
            zoneMode = zoneAvailable();
            beginRecording();
        } else {
            finishRecording();
        }
    }

    private void beginRecording() {
        if (isRecording || isProcessing) return;
        isRecording = true;
        setBubbleState(true);
        if (AudioFocusPauser.isEnabled(this)) audioPauser.request(this);
        DictationAccessibilityService.resetLive();
        liveTyping = DictationAccessibilityService.isRunning() && isLiveTypingEnabled();
        startRecording(isAutoStopEnabled(), liveTyping);
        updateStopZone();
    }

    private boolean zoneAvailable() {
        Rect kb = DictationAccessibilityService.getKeyboardBounds();
        return FloatingMicPrefs.isStopZoneEnabled(this)
                && DictationAccessibilityService.isRunning()
                && kb != null && kb.width() > 0 && kb.height() > 0;
    }

    private void finishRecording() {
        if (!isRecording) return;
        isRecording = false;
        isProcessing = true;
        if (zoneMode) updateStopZone(); else removeStopZone();
        bubble.setAlpha(opacity * 0.6f);
        stopRecording();
        audioPauser.abandon(this);
    }

    /**
     * While recording, a translucent zone over the keyboard stops the recording when
     * tapped, so the finger that is about to press "send" doesn't have to travel back
     * to the bubble. It follows the keyboard's rectangle, and is only drawn when that
     * rectangle is known (accessibility service on) so it never covers app content.
     */
    @SuppressLint("ClickableViewAccessibility")
    private void updateStopZone() {
        Rect kb = clampToScreen(DictationAccessibilityService.getKeyboardBounds());
        boolean show = zoneMode
                && FloatingMicPrefs.isStopZoneEnabled(this)
                && DictationAccessibilityService.isRunning()
                && kb != null && kb.width() > 0 && kb.height() > 0;
        if (!show) {
            removeStopZone();
            return;
        }

        if (stopZone == null) {
            stopZone = new FrameLayout(this);
            stopZone.setContentDescription(getString(R.string.floating_mic_stop_zone_label));
            // The mic "takes over" the keyboard: a big mic that pulses with the voice,
            // like the bubble does, with the hint underneath.
            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER);
            stopZoneIcon = new ImageView(this);
            stopZoneIcon.setImageResource(R.drawable.ic_mic);
            stopZoneIcon.setColorFilter(0xFFFFFFFF);
            int iconSize = (int) (72 * density);
            content.addView(stopZoneIcon, new LinearLayout.LayoutParams(iconSize, iconSize));
            TextView label = new TextView(this);
            stopZoneLabel = label;
            label.setTextColor(0xFFFFFFFF);
            label.setTextSize(16);
            label.setGravity(Gravity.CENTER);
            label.setShadowLayer(4 * density, 0, 0, 0xFF000000);
            content.addView(label, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            stopZone.addView(content, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER));
            stopZone.setOnClickListener(v -> {
                if (isProcessing) return;
                if (isRecording) finishRecording(); else beginRecording();
            });

            // Idle: split in two. Left (green) starts a new dictation, right (blue) closes
            // the big zone, so the keyboard is usable again.
            stopZoneIdle = new LinearLayout(this);
            stopZoneIdle.setOrientation(LinearLayout.HORIZONTAL);
            stopZoneIdle.addView(idlePanel(0x3352C77C, R.drawable.ic_mic,
                    R.string.floating_mic_zone_start, this::beginRecording),
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
            stopZoneIdle.addView(idlePanel(0x334A90FF, R.drawable.ic_close,
                    R.string.floating_mic_zone_close, this::closeZoneMode),
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
            stopZone.addView(stopZoneIdle, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            stopZoneContent = content;

            // NOT_FOCUSABLE keeps the input focus in the app, so the text can still be typed in.
            stopZoneParams = new WindowManager.LayoutParams(
                    kb.width(), kb.height(),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            stopZoneParams.gravity = Gravity.TOP | Gravity.START;
            // Without this, devices with a cut-out/status bar may place the window below it,
            // shifting the zone (and its centred mic) down from the keyboard.
            if (Build.VERSION.SDK_INT >= 28) {
                stopZoneParams.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            }
            stopZoneParams.x = kb.left;
            stopZoneParams.y = kb.top;
            try {
                windowManager.addView(stopZone, stopZoneParams);
                bringBubbleToFront();
            } catch (Exception e) {
                Log.e(TAG, "Failed to add the stop zone", e);
                stopZone = null;
            }
        } else if (stopZoneParams.x != kb.left || stopZoneParams.y != kb.top
                || stopZoneParams.width != kb.width() || stopZoneParams.height != kb.height()) {
            stopZoneParams.x = kb.left;
            stopZoneParams.y = kb.top;
            stopZoneParams.width = kb.width();
            stopZoneParams.height = kb.height();
            try {
                windowManager.updateViewLayout(stopZone, stopZoneParams);
            } catch (Exception ignored) { }
        }
        applyZoneState();
    }

    /**
     * The keyboard's reported rectangle can reach past the visible screen (under the
     * navigation bar); the zone is clamped to what is on screen so its content is
     * centred on the part the user actually sees.
     */
    private Rect clampToScreen(Rect r) {
        if (r == null) return null;
        int[] sz = screenSize();
        Rect c = new Rect(r);
        c.left = Math.max(0, c.left);
        c.top = Math.max(0, c.top);
        c.right = Math.min(sz[0], c.right);
        c.bottom = Math.min(sz[1], c.bottom);
        return c;
    }

    private void closeZoneMode() {
        zoneMode = false;
        removeStopZone();
    }

    /** One half of the idle zone: tinted background, icon and label, tappable. */
    private LinearLayout idlePanel(int bg, int iconRes, int labelRes, Runnable onTap) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setBackgroundColor(bg);
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(0xFFFFFFFF);
        int size = (int) (56 * density);
        panel.addView(icon, new LinearLayout.LayoutParams(size, size));
        TextView label = new TextView(this);
        label.setText(labelRes);
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(16);
        label.setGravity(Gravity.CENTER);
        label.setShadowLayer(4 * density, 0, 0, 0xFF000000);
        panel.addView(label, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        panel.setOnClickListener(v -> onTap.run());
        return panel;
    }

    /** Colour and label of the big zone follow the state: idle, recording, transcribing. */
    private void applyZoneState() {
        if (stopZone == null) return;
        boolean idle = !isRecording && !isProcessing;
        stopZoneIdle.setVisibility(idle ? View.VISIBLE : View.GONE);
        stopZoneContent.setVisibility(idle ? View.GONE : View.VISIBLE);
        if (isRecording) {
            stopZone.setBackgroundColor(0x33FF5252);
            stopZoneLabel.setText(R.string.floating_mic_stop_zone_label);
        } else if (isProcessing) {
            stopZone.setBackgroundColor(0x33888888);
            stopZoneLabel.setText(R.string.floating_mic_zone_processing);
            stopZoneIcon.setScaleX(1f);
            stopZoneIcon.setScaleY(1f);
        } else {
            stopZone.setBackgroundColor(0x00000000);
        }
    }

    /**
     * Overlay windows stack in the order they were added, so the zone (added later) would sit
     * on top of the bubble and swallow its taps. Re-adding the bubble puts it back on top.
     * Posted so it doesn't run inside the bubble's own touch handler.
     */
    private void bringBubbleToFront() {
        mainHandler.post(() -> {
            if (bubble == null || stopZone == null) return;
            try {
                windowManager.removeViewImmediate(bubble);
                windowManager.addView(bubble, params);
            } catch (Exception e) {
                Log.w(TAG, "Could not raise the bubble above the zone", e);
            }
        });
    }

    private void removeStopZone() {
        if (stopZone == null) return;
        try { windowManager.removeView(stopZone); } catch (Exception ignored) { }
        stopZone = null;
        stopZoneIcon = null;
        stopZoneLabel = null;
        stopZoneIdle = null;
        stopZoneContent = null;
    }

    private void setBubbleState(boolean recording) {
        bubble.setScaleX(1f);
        bubble.setScaleY(1f);
        bubble.setAlpha(opacity);
        micIcon.setColorFilter(recording ? 0xFFFF6B6B : 0xB3E8DEF8);
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
                audioPauser.abandon(this);
                removeStopZone();
                if (bubble != null) { setBubbleState(false); updateVisibility(); }
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
                if (stopZoneIcon != null) {
                    stopZoneIcon.setScaleX(scale);
                    stopZoneIcon.setScaleY(scale);
                }
            }
        });
    }

    // Called from Rust while recording (live typing): finished sentences are
    // kept, the sentence in progress is rewritten on every update.
    public void onPartialText(String text, boolean isFinal) {
        mainHandler.post(() -> {
            if (!isRecording || !liveTyping) return;
            String t = isFinal && !text.isEmpty() ? text + " " : text;
            DictationAccessibilityService.insertLive(this, t, isFinal);
        });
    }

    public void onTextTranscribed(String text) {
        mainHandler.post(() -> {
            isProcessing = false;
            if (bubble != null) { setBubbleState(false); updateVisibility(); }
            if (liveTyping) {
                liveTyping = false;
                // Replace the last partial with the final remainder (or drop it).
                String t = text == null ? "" : text.trim();
                DictationAccessibilityService.insertLive(this, t.isEmpty() ? "" : t + " ", true);
                DictationAccessibilityService.resetLive();
                return;
            }
            if (text == null || text.trim().isEmpty()) return;
            if (!DictationAccessibilityService.insert(this, text)) {
                Toast.makeText(this, R.string.floating_mic_copied, Toast.LENGTH_LONG).show();
            }
        });
    }

    // --- Lifecycle -----------------------------------------------------------

    @Override
    public void onDestroy() {
        DictationAccessibilityService.setKeyboardListener(null);
        displayManager.unregisterDisplayListener(displayListener);
        mainHandler.removeCallbacks(repositionRunnable);
        if (isRecording) {
            try { cancelRecording(); } catch (Throwable t) { /* ignore */ }
        }
        audioPauser.abandon(this);
        removeStopZone();
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
    /** Live typing is default ON; the marker file is the opt-out. */
    private boolean isLiveTypingEnabled() {
        return !new java.io.File(getFilesDir(), "no_live_typing").exists();
    }

    private native void startRecording(boolean autoStop, boolean live);
    private native void stopRecording();
    private native void cancelRecording();
}
