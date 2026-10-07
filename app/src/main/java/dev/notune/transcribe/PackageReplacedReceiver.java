package dev.notune.transcribe;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

/**
 * An app update (or a reboot) kills the floating mic service and can leave the accessibility
 * service unbound, so the user had to switch both off and on again. This brings both back.
 */
public class PackageReplacedReceiver extends BroadcastReceiver {
    private static final String TAG = "PackageReplacedRcv";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !Intent.ACTION_BOOT_COMPLETED.equals(action)) return;
        if (!FloatingMicPrefs.isEnabled(ctx)) return;

        reEnableAccessibility(ctx);
        FloatingMicService.startIfPossible(ctx);
    }

    /**
     * Android normally re-binds an enabled accessibility service after an update, but not always.
     * With WRITE_SECURE_SETTINGS (granted once with
     * {@code adb shell pm grant dev.notune.transcribe android.permission.WRITE_SECURE_SETTINGS})
     * we can make sure it is listed as enabled; without it nothing can be done from here.
     */
    private static void reEnableAccessibility(Context ctx) {
        if (ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) return;
        try {
            String me = new ComponentName(ctx, DictationAccessibilityService.class).flattenToString();
            String cur = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (cur == null) cur = "";
            boolean listed = false;
            StringBuilder others = new StringBuilder();
            for (String s : cur.split(":")) {
                if (s.isEmpty()) continue;
                if (s.equals(me)) { listed = true; continue; }
                if (others.length() > 0) others.append(':');
                others.append(s);
            }
            // Drop it and put it back so the system binds it afresh, even if it thinks it already did.
            if (listed && !DictationAccessibilityService.isRunning()) {
                Settings.Secure.putString(ctx.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.toString());
            }
            String list = others.length() == 0 ? me : others + ":" + me;
            Settings.Secure.putString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list);
            Settings.Secure.putInt(ctx.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not re-enable the accessibility service", e);
        }
    }
}
