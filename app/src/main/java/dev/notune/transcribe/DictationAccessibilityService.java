package dev.notune.transcribe;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Types dictated text into the focused input of whatever app is on screen.
 * Only used by the floating mic button; it never reads or stores screen content.
 */
public class DictationAccessibilityService extends AccessibilityService {
    private static volatile DictationAccessibilityService instance;

    public static boolean isRunning() {
        return instance != null;
    }

    /**
     * Pastes {@code text} at the cursor of the focused editable field.
     * Returns false (with the text left on the clipboard) if no field has focus.
     */
    public static boolean insert(Context ctx, String text) {
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("dictation", text));

        DictationAccessibilityService svc = instance;
        if (svc == null) return false;
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (field == null || !field.isEditable()) return false;
        return field.performAction(AccessibilityNodeInfo.ACTION_PASTE);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }
}
