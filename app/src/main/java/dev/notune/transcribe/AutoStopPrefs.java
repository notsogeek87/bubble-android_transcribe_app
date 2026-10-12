package dev.notune.transcribe;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Auto-stop silence delay, stored as a small file in filesDir (also read by
 * the native recorder, in any process).
 */
public final class AutoStopPrefs {
    private static final String FILE_NAME = "auto_stop_ms";
    public static final int DEFAULT_MS = 2000;

    private AutoStopPrefs() {}

    public static int getDelayMs(Context ctx) {
        File f = new File(ctx.getFilesDir(), FILE_NAME);
        if (!f.exists()) return DEFAULT_MS;
        try {
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
            return Integer.parseInt(s);
        } catch (IOException | NumberFormatException e) {
            return DEFAULT_MS;
        }
    }

    public static void setDelayMs(Context ctx, int ms) {
        File f = new File(ctx.getFilesDir(), FILE_NAME);
        try {
            Files.write(f.toPath(), String.valueOf(ms).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
    }
}
