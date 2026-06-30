package com.mateo.hojadevuelo.ocr;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/** Evidencias locales disponibles únicamente cuando el APK es depurable. */
final class OcrDiagnostics {
    private static final String DIRECTORY = "ocr-diagnostics";

    private OcrDiagnostics() {
    }

    static void saveColumn(Context context, Bitmap bitmap) {
        if (!isDebuggable(context)) return;
        File file = diagnosticFile(context, "last-column.jpg");
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 96, output);
        } catch (IOException ignored) {
            // El diagnóstico nunca debe impedir el reconocimiento.
        }
    }

    static void saveRawText(Context context, String text) {
        if (!isDebuggable(context)) return;
        File file = diagnosticFile(context, "last-ocr.txt");
        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(file, false), StandardCharsets.UTF_8)) {
            writer.write(text == null ? "" : text);
        } catch (IOException ignored) {
            // El diagnóstico nunca debe impedir el reconocimiento.
        }
    }

    private static File diagnosticFile(Context context, String name) {
        File directory = new File(context.getFilesDir(), DIRECTORY);
        if (!directory.exists()) {
            directory.mkdirs();
        }
        return new File(directory, name);
    }

    private static boolean isDebuggable(Context context) {
        return (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }
}
