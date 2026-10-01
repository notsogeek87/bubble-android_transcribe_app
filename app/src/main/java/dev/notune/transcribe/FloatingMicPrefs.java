package dev.notune.transcribe;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Floating mic button preferences, stored as small files in filesDir like the other settings. */
public final class FloatingMicPrefs {
    private static final String ENABLED_FILE = "floating_mic";
    private static final String POS_FILE = "floating_mic_pos";

    private FloatingMicPrefs() {}

    public static boolean isEnabled(Context ctx) {
        return new File(ctx.getFilesDir(), ENABLED_FILE).exists();
    }

    public static void setEnabled(Context ctx, boolean on) {
        File f = new File(ctx.getFilesDir(), ENABLED_FILE);
        if (on) {
            try { f.createNewFile(); } catch (IOException ignored) { }
        } else {
            f.delete();
        }
    }

    /** Returns {x, y} of the bubble, or null if the user never moved it. */
    public static int[] getPosition(Context ctx) {
        File f = new File(ctx.getFilesDir(), POS_FILE);
        if (!f.exists()) return null;
        try {
            String[] p = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim().split(",");
            return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1])};
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public static void setPosition(Context ctx, int x, int y) {
        File f = new File(ctx.getFilesDir(), POS_FILE);
        try {
            Files.write(f.toPath(), (x + "," + y).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
    }
}
