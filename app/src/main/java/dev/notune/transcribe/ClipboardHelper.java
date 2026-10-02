package dev.notune.transcribe;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;

/**
 * Puts transcriptions on the clipboard without leaving them there: the clip is
 * flagged as sensitive (hidden from the clipboard preview and from keyboards'
 * clipboard history) and cleared again after a delay.
 */
final class ClipboardHelper {
    /** Clipboard used only to paste into a field: cleared once the paste is done. */
    static final long PASTE_CLEAR_MS = 1_000;
    /** Clipboard handed to the user to paste themselves. */
    static final long MANUAL_CLEAR_MS = 2 * 60_000;

    private static final String LABEL = "dev.notune.transcribe";
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static Runnable pendingClear;

    private ClipboardHelper() { }

    static void copy(Context ctx, String text, long clearAfterMs) {
        Context app = ctx.getApplicationContext();
        ClipboardManager cm = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(LABEL, text);
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
        clip.getDescription().setExtras(extras);
        cm.setPrimaryClip(clip);

        if (pendingClear != null) handler.removeCallbacks(pendingClear);
        pendingClear = () -> {
            pendingClear = null;
            clearIfOurs(cm, text);
        };
        handler.postDelayed(pendingClear, clearAfterMs);
    }

    private static void clearIfOurs(ClipboardManager cm, String text) {
        ClipData current = null;
        try {
            current = cm.getPrimaryClip();
        } catch (SecurityException ignored) { }
        // Leave alone anything the user copied in the meantime. In the background,
        // Android 10+ hides the clipboard (null): clear it anyway rather than leave
        // the transcription there.
        if (current != null) {
            CharSequence label = current.getDescription().getLabel();
            CharSequence clipText = current.getItemCount() > 0 ? current.getItemAt(0).getText() : null;
            if (!LABEL.contentEquals(label == null ? "" : label)
                    || !text.contentEquals(clipText == null ? "" : clipText)) {
                return;
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                cm.clearPrimaryClip();
            } else {
                cm.setPrimaryClip(ClipData.newPlainText("", ""));
            }
        } catch (SecurityException ignored) { }
    }
}
