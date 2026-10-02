package dev.notune.transcribe;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

import java.io.File;

/**
 * Silences other apps' audio while we record.
 *
 * First asks for transient exclusive audio focus, which well-behaved players
 * honour by pausing. Many players (and car / Bluetooth head units) ignore it
 * or only lower their volume, so shortly afterwards we check whether music is
 * still playing and, if so, send a media-pause key. Only a pause we sent
 * ourselves is undone with a media-play key when recording ends.
 *
 * Some players (Deezer on some phones) ignore both. As a last resort the media
 * volume is muted for the duration of the recording and restored afterwards;
 * the music keeps playing silently, but the microphone no longer hears it.
 */
public class AudioFocusPauser {
    /** Marker file backing the "Pause audio" setting. */
    private static final String MARKER = "pause_audio";
    /** Time players get to react to the focus request before we check them. */
    private static final long FOCUS_SETTLE_MS = 300;
    /** Time the media-pause key gets to take effect before we fall back to muting. */
    private static final long KEY_SETTLE_MS = 500;
    /** Present while we hold the media volume muted, so a killed process can still restore it. */
    private static final String MUTED_MARKER = "muted_by_pause_audio";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioFocusRequest focusRequest = null;
    private AudioManager.OnAudioFocusChangeListener listener = focusChange -> { };
    private boolean pausedByKey = false;
    private Runnable pendingCheck = null;
    private Runnable pendingMuteCheck = null;

    public static boolean isEnabled(Context ctx) {
        return new File(ctx.getFilesDir(), MARKER).exists();
    }

    public void request(Context ctx) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;

            // always release first
            abandon(ctx);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest = new AudioFocusRequest.Builder(
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setOnAudioFocusChangeListener(listener)
                        .build();
                am.requestAudioFocus(focusRequest);
            } else {
                // Pre-O: best effort
                am.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            }

            // Players that ignored the focus request keep playing: pause them.
            pendingCheck = () -> {
                pendingCheck = null;
                if (!am.isMusicActive()) return;
                dispatchMediaKey(am, KeyEvent.KEYCODE_MEDIA_PAUSE);
                pausedByKey = true;

                // Still playing after the pause key: mute it instead.
                pendingMuteCheck = () -> {
                    pendingMuteCheck = null;
                    if (am.isMusicActive()) muteMusic(ctx, am);
                };
                handler.postDelayed(pendingMuteCheck, KEY_SETTLE_MS);
            };
            handler.postDelayed(pendingCheck, FOCUS_SETTLE_MS);
        } catch (Exception ignored) { }
    }

    public void abandon(Context ctx) {
        try {
            if (pendingCheck != null) {
                handler.removeCallbacks(pendingCheck);
                pendingCheck = null;
            }
            if (pendingMuteCheck != null) {
                handler.removeCallbacks(pendingMuteCheck);
                pendingMuteCheck = null;
            }

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

            unmuteMusic(ctx, am);

            if (pausedByKey) {
                pausedByKey = false;
                dispatchMediaKey(am, KeyEvent.KEYCODE_MEDIA_PLAY);
            }
        } catch (Exception ignored) { }
    }

    /** Mutes the media stream unless it was already muted, remembering that we did. */
    private static void muteMusic(Context ctx, AudioManager am) {
        try {
            File marker = new File(ctx.getFilesDir(), MUTED_MARKER);
            if (marker.exists() || am.isStreamMute(AudioManager.STREAM_MUSIC)) return;
            marker.createNewFile();
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
        } catch (Exception ignored) { }
    }

    /** Restores the media stream, but only if we were the ones who muted it. */
    private static void unmuteMusic(Context ctx, AudioManager am) {
        File marker = new File(ctx.getFilesDir(), MUTED_MARKER);
        if (!marker.exists()) return;
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
        marker.delete();
    }

    private static void dispatchMediaKey(AudioManager am, int keyCode) {
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
    }
}
