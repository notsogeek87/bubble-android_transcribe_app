package dev.notune.transcribe;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Personal vocabulary rules ("heard => wanted", one per line), stored as a
 * text file in filesDir and applied by the native engine to every transcript.
 */
public final class VocabularyPrefs {
    private static final String FILE_NAME = "vocabulary.txt";

    private VocabularyPrefs() {}

    public static String get(Context ctx) {
        File f = new File(ctx.getFilesDir(), FILE_NAME);
        if (!f.exists()) return "";
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    public static void set(Context ctx, String rules) {
        File f = new File(ctx.getFilesDir(), FILE_NAME);
        try {
            Files.write(f.toPath(), rules.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
    }
}
