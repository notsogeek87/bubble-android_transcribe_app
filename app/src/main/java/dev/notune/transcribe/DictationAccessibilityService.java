package dev.notune.transcribe;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

/**
 * Types dictated text into the focused input of whatever app is on screen and
 * reports whether the on-screen keyboard is open.
 * Only used by the floating mic button; it never reads or stores screen content.
 */
public class DictationAccessibilityService extends AccessibilityService {
    public interface KeyboardListener {
        void onKeyboardVisibilityChanged(boolean visible);
    }

    private static volatile DictationAccessibilityService instance;
    private static volatile KeyboardListener keyboardListener;
    private static volatile boolean keyboardVisible;

    public static boolean isRunning() {
        return instance != null;
    }

    public static boolean isKeyboardVisible() {
        return keyboardVisible;
    }

    public static void setKeyboardListener(KeyboardListener l) {
        keyboardListener = l;
    }

    /**
     * Inserts {@code text} at the cursor of the focused editable field.
     * Returns false (with the text left on the clipboard) if that is not possible.
     */
    public static boolean insert(Context ctx, String text) {
        DictationAccessibilityService svc = instance;
        if (svc != null) {
            AccessibilityNodeInfo field = svc.findFocusedEditable();
            if (field != null && (setText(field, text) || paste(ctx, field, text))) {
                return true;
            }
        }
        copy(ctx, text);
        return false;
    }

    private static void copy(Context ctx, String text) {
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("dictation", text));
    }

    private static boolean paste(Context ctx, AccessibilityNodeInfo field, String text) {
        copy(ctx, text);
        return field.performAction(AccessibilityNodeInfo.ACTION_PASTE);
    }

    /** Replaces the selection (or inserts at the cursor) without going through the clipboard. */
    private static boolean setText(AccessibilityNodeInfo field, String text) {
        CharSequence current = field.isShowingHintText() ? null : field.getText();
        String old = current == null ? "" : current.toString();
        int start = field.getTextSelectionStart();
        int end = field.getTextSelectionEnd();
        if (start < 0 || end < 0 || start > old.length() || end > old.length()) {
            start = end = old.length();
        }
        int from = Math.min(start, end);
        int to = Math.max(start, end);
        String merged = old.substring(0, from) + text + old.substring(to);

        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, merged);
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false;

        Bundle sel = new Bundle();
        int caret = from + text.length();
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret);
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret);
        field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel);
        return true;
    }

    private AccessibilityNodeInfo findFocusedEditable() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (f != null && f.isEditable()) return f;
        }
        // The active window can be the keyboard or an overlay: look in every app window.
        List<AccessibilityWindowInfo> windows = getWindows();
        for (AccessibilityWindowInfo w : windows) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo r = w.getRoot();
            if (r == null) continue;
            AccessibilityNodeInfo f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (f != null && f.isEditable()) return f;
        }
        return null;
    }

    private void updateKeyboardVisibility() {
        boolean visible = false;
        List<AccessibilityWindowInfo> windows = getWindows();
        for (AccessibilityWindowInfo w : windows) {
            if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                visible = true;
                break;
            }
        }
        if (visible == keyboardVisible) return;
        keyboardVisible = visible;
        KeyboardListener l = keyboardListener;
        if (l != null) l.onKeyboardVisibilityChanged(visible);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        updateKeyboardVisibility();
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        keyboardVisible = false;
        KeyboardListener l = keyboardListener;
        if (l != null) l.onKeyboardVisibilityChanged(false);
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int t = event.getEventType();
        if (t == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                || t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            updateKeyboardVisibility();
        }
    }

    @Override
    public void onInterrupt() { }
}
