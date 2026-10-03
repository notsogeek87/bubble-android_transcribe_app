package dev.notune.transcribe;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
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

    private static final String TAG = "DictationA11y";
    private static volatile DictationAccessibilityService instance;
    private static volatile KeyboardListener keyboardListener;
    private static volatile boolean keyboardVisible;
    /** Screen rectangle of the keyboard while it is shown; null otherwise. */
    private static volatile Rect keyboardBounds;

    public static boolean isRunning() {
        return instance != null;
    }

    public static boolean isKeyboardVisible() {
        return keyboardVisible;
    }

    /** A copy of the keyboard's on-screen rectangle, or null when no keyboard is shown. */
    public static Rect getKeyboardBounds() {
        Rect b = keyboardBounds;
        return b == null ? null : new Rect(b);
    }

    public static void setKeyboardListener(KeyboardListener l) {
        keyboardListener = l;
    }

    /**
     * Inserts {@code text} at the cursor of the focused editable field.
     * Returns false if that is not possible; the text is then dropped, never left on the clipboard.
     */
    public static boolean insert(Context ctx, String text) {
        DictationAccessibilityService svc = instance;
        if (svc != null) {
            AccessibilityNodeInfo field = svc.findFocusedEditable();
            if (field != null) {
                // The app knows better than we do what is real text and what is placeholder,
                // so for the apps that don't tell us, let it insert the text itself.
                boolean done = exposesPlaceholderAsText(field)
                        ? paste(ctx, field, text) || setText(field, text)
                        : setText(field, text) || paste(ctx, field, text);
                if (done) return true;
            }
        }
        return false;
    }

    /**
     * Apps whose text box reports its placeholder ("Message") as the field's text, with no
     * hint or flag to tell it apart from what was typed. Merging our text with the exposed
     * text would put the placeholder in front of the dictation.
     */
    private static final String[] PLACEHOLDER_AS_TEXT_PACKAGES = {"com.whatsapp", "com.whatsapp.w4b"};

    private static boolean exposesPlaceholderAsText(AccessibilityNodeInfo field) {
        CharSequence pkg = field.getPackageName();
        if (pkg == null) return false;
        for (String p : PLACEHOLDER_AS_TEXT_PACKAGES) {
            if (p.contentEquals(pkg)) return true;
        }
        return false;
    }

    private static boolean paste(Context ctx, AccessibilityNodeInfo field, String text) {
        ClipboardHelper.copy(ctx, text, ClipboardHelper.PASTE_CLEAR_MS);
        return field.performAction(AccessibilityNodeInfo.ACTION_PASTE);
    }

    /** Replaces the selection (or inserts at the cursor) without going through the clipboard. */
    private static boolean setText(AccessibilityNodeInfo field, String text) {
        CharSequence current = field.isShowingHintText() ? null : field.getText();
        String old = current == null ? "" : current.toString();
        // Some apps (Telegram) expose the placeholder as the field's text without flagging it.
        CharSequence hint = field.getHintText();
        if (hint != null && old.contentEquals(hint)) old = "";
        CharSequence desc = field.getContentDescription();
        if (desc != null && old.contentEquals(desc)) old = "";
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

    private static boolean isTextField(AccessibilityNodeInfo n) {
        if (n.isEditable()) return true;
        CharSequence cls = n.getClassName();
        if (cls != null && cls.toString().contains("EditText")) return true;
        return n.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT);
    }

    /**
     * Finds the text field being typed in. Native fields report input focus directly;
     * web views, Compose and cross-platform UIs often don't, so fall back to scanning the tree.
     */
    private AccessibilityNodeInfo findFocusedEditable() {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null) roots.add(active);
        for (AccessibilityWindowInfo w : getWindows()) {
            int type = w.getType();
            if (type != AccessibilityWindowInfo.TYPE_APPLICATION
                    && type != AccessibilityWindowInfo.TYPE_SYSTEM) continue;
            AccessibilityNodeInfo r = w.getRoot();
            if (r != null) roots.add(r);
        }

        for (AccessibilityNodeInfo r : roots) {
            for (int focusType : new int[]{AccessibilityNodeInfo.FOCUS_INPUT,
                    AccessibilityNodeInfo.FOCUS_ACCESSIBILITY}) {
                AccessibilityNodeInfo f = r.findFocus(focusType);
                if (f != null && isTextField(f)) return f;
            }
        }
        for (AccessibilityNodeInfo r : roots) {
            AccessibilityNodeInfo f = scanForFocusedField(r, new int[]{0});
            if (f != null) return f;
        }
        Log.d(TAG, "No focused text field found in " + roots.size() + " window(s)");
        return null;
    }

    private static AccessibilityNodeInfo scanForFocusedField(AccessibilityNodeInfo n, int[] budget) {
        if (n == null || budget[0]++ > 3000) return null;
        if (n.isFocused() && isTextField(n)) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo f = scanForFocusedField(n.getChild(i), budget);
            if (f != null) return f;
        }
        return null;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    // Accessibility events about the keyboard closing arrive late, so while it is
    // open we also check the windows ourselves about 20 times per second.
    private final Runnable keyboardPoll = new Runnable() {
        @Override public void run() {
            updateKeyboardVisibility();
            if (keyboardVisible) handler.postDelayed(this, 50);
        }
    };

    // A field gaining focus (the app opening its keyboard) is not always followed by a
    // windows event we get, so re-check for a short while after it.
    private int recheckLeft;
    private final Runnable keyboardRecheck = new Runnable() {
        @Override public void run() {
            updateKeyboardVisibility();
            if (--recheckLeft > 0) handler.postDelayed(this, 150);
        }
    };

    /** Bounds of the visible keyboard window, or null if there is none. */
    private Rect findKeyboardBounds() {
        android.util.DisplayMetrics m = new android.util.DisplayMetrics();
        ((android.view.WindowManager) getSystemService(WINDOW_SERVICE))
                .getDefaultDisplay().getRealMetrics(m);
        int screenHeight = m.heightPixels;
        Rect r = new Rect();
        for (AccessibilityWindowInfo w : getWindows()) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
            w.getBoundsInScreen(r);
            // A keyboard sliding away or collapsed to nothing no longer counts.
            Log.d(TAG, "IME window " + r + " screenH=" + screenHeight);
            if (r.height() <= 0 || r.top >= screenHeight) continue;
            // The keyboard's window lingers a moment after it is hidden, but its content
            // stops being visible straight away.
            AccessibilityNodeInfo root = w.getRoot();
            if (root != null) {
                root.refresh();
                if (!root.isVisibleToUser()) {
                    Log.d(TAG, "IME window not visible to user, ignored");
                    continue;
                }
            }
            return new Rect(r);
        }
        return null;
    }

    private void updateKeyboardVisibility() {
        Rect bounds = findKeyboardBounds();
        boolean visible = bounds != null;
        boolean visibilityChanged = visible != keyboardVisible;
        boolean boundsChanged = visible && !bounds.equals(keyboardBounds);
        keyboardBounds = bounds;
        if (!visibilityChanged && !boundsChanged) return;
        if (visibilityChanged) {
            keyboardVisible = visible;
            handler.removeCallbacks(keyboardPoll);
            if (visible) handler.postDelayed(keyboardPoll, 50);
        }
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
        handler.removeCallbacks(keyboardPoll);
        handler.removeCallbacks(keyboardRecheck);
        keyboardVisible = false;
        keyboardBounds = null;
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
        } else if (t == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            recheckLeft = 10;
            handler.removeCallbacks(keyboardRecheck);
            handler.post(keyboardRecheck);
        }
    }

    @Override
    public void onInterrupt() { }
}
