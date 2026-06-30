package com.mateo.hojadevuelo.ocr;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognizer;
import com.mateo.hojadevuelo.domain.RegistrationMatcher;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** OCR local especializado en una columna estrecha de matrículas. */
public final class RegistrationColumnOcrProcessor {
    public interface Listener {
        void onProgress(int completedPasses, int totalPasses);
        void onSuccess(List<String> registrations, boolean textWasDetected);
        void onError(Exception error);
    }

    private static final int[] ROW_GRIDS = {4, 8};
    private static final float VERTICAL_OVERLAP = 0.20f;
    private static final int TARGET_STRIP_WIDTH = 1400;
    private static final int MAX_PASS_DIMENSION = 2600;

    private final TextRecognizer recognizer;
    private final RegistrationMatcher matcher;
    private final List<String> catalogRegistrations;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int generation;
    private boolean closed;
    private HandwritingOcrEngine handwritingEngine;

    public RegistrationColumnOcrProcessor(
            TextRecognizer recognizer,
            RegistrationMatcher matcher,
            List<String> catalogRegistrations) {
        this.recognizer = recognizer;
        this.matcher = matcher;
        this.catalogRegistrations = new ArrayList<>(catalogRegistrations);
    }

    /**
     * Consume el bitmap recibido: lo recicla al terminar o al cancelar el análisis.
     */
    public void process(Context context, Bitmap column, Listener listener) {
        int requestGeneration = ++generation;
        List<Rect> regions = createColumnRegions(column.getWidth(), column.getHeight());
        List<Pass> mlKitPasses = new ArrayList<>(regions.size() * 2);
        for (Rect region : regions) {
            mlKitPasses.add(new Pass(region, Enhancement.ORIGINAL));
            mlKitPasses.add(new Pass(region, Enhancement.CONTRAST));
        }

        Context appContext = context.getApplicationContext();
        worker.execute(() -> {
            OcrDiagnostics.saveColumn(appContext, column);
            try {
                if (handwritingEngine == null) {
                    handwritingEngine = new HandwritingOcrEngine(
                            appContext,
                            catalogRegistrations);
                }
                List<String> handwritingMatches = handwritingEngine.recognize(column);
                if (!handwritingMatches.isEmpty()) {
                    OcrDiagnostics.saveRawText(
                            appContext,
                            "PP-OCRv6 manuscrito: " + handwritingMatches);
                    recycle(column);
                    mainHandler.post(() -> {
                        if (!isStale(requestGeneration)) {
                            listener.onProgress(1, 1);
                            listener.onSuccess(handwritingMatches, true);
                        }
                    });
                    return;
                }
            } catch (Exception ignored) {
                // La ruta clásica se conserva como respaldo si ONNX falla.
            }
            mainHandler.post(() -> processMlKitPass(
                    appContext,
                    column,
                    regions,
                    mlKitPasses,
                    0,
                    requestGeneration,
                    new LinkedHashSet<>(),
                    new StringBuilder(),
                    false,
                    0,
                    null,
                    listener));
        });
    }

    private void processMlKitPass(
            Context context,
            Bitmap source,
            List<Rect> tesseractRegions,
            List<Pass> passes,
            int index,
            int requestGeneration,
            Set<String> matches,
            StringBuilder diagnosticText,
            boolean textWasDetected,
            int successfulPasses,
            Exception lastError,
            Listener listener) {
        if (isStale(requestGeneration)) {
            recycle(source);
            return;
        }
        if (index >= passes.size()) {
            if (successfulPasses == 0 && lastError != null) {
                OcrDiagnostics.saveRawText(context, diagnosticText.toString());
                recycle(source);
                listener.onError(lastError);
                return;
            }
            processWithTesseract(
                    context,
                    source,
                    tesseractRegions,
                    passes.size(),
                    requestGeneration,
                    matches,
                    diagnosticText,
                    textWasDetected,
                    listener);
            return;
        }

        Bitmap passBitmap = createPassBitmap(source, passes.get(index));
        final boolean[] detectedText = {textWasDetected};
        final int[] successes = {successfulPasses};
        final Exception[] error = {lastError};

        recognizer.process(InputImage.fromBitmap(passBitmap, 0))
                .addOnSuccessListener(text -> {
                    String value = text.getText();
                    if (value != null && !value.trim().isEmpty()) {
                        detectedText[0] = true;
                    }
                    appendDiagnostic(diagnosticText, "ML Kit " + (index + 1), value);
                    matches.addAll(matcher.findRegistrations(value));
                    successes[0]++;
                })
                .addOnFailureListener(failure -> error[0] = failure)
                .addOnCompleteListener(ignored -> {
                    recycle(passBitmap);
                    int completed = index + 1;
                    int total = passes.size() + tesseractRegions.size() * 2;
                    listener.onProgress(completed, total);
                    processMlKitPass(
                            context,
                            source,
                            tesseractRegions,
                            passes,
                            completed,
                            requestGeneration,
                            matches,
                            diagnosticText,
                            detectedText[0],
                            successes[0],
                            error[0],
                            listener);
                });
    }

    private void processWithTesseract(
            Context context,
            Bitmap source,
            List<Rect> regions,
            int completedMlKitPasses,
            int requestGeneration,
            Set<String> matches,
            StringBuilder diagnosticText,
            boolean textWasDetected,
            Listener listener) {
        int totalPasses = completedMlKitPasses + regions.size() * 2;
        worker.execute(() -> {
            boolean detectedText = textWasDetected;
            try (TesseractOcrEngine tesseract = new TesseractOcrEngine()) {
                tesseract.initialize(context);
                int completed = completedMlKitPasses;
                for (Rect region : regions) {
                    for (Enhancement enhancement
                            : new Enhancement[]{Enhancement.CONTRAST, Enhancement.THRESHOLD}) {
                        if (isStale(requestGeneration)) {
                            recycle(source);
                            return;
                        }
                        Bitmap passBitmap = createPassBitmap(
                                source,
                                new Pass(region, enhancement));
                        try {
                            String value = tesseract.recognize(passBitmap);
                            if (value != null && !value.trim().isEmpty()) {
                                detectedText = true;
                            }
                            appendDiagnostic(
                                    diagnosticText,
                                    "Tesseract " + (completed + 1),
                                    value);
                            matches.addAll(matcher.findRegistrations(value));
                        } finally {
                            recycle(passBitmap);
                        }
                        completed++;
                        int progress = completed;
                        mainHandler.post(() -> {
                            if (!isStale(requestGeneration)) {
                                listener.onProgress(progress, totalPasses);
                            }
                        });
                    }
                }
            } catch (Exception ignored) {
                // ML Kit sigue proporcionando un resultado si Tesseract no está disponible.
            }

            boolean finalTextWasDetected = detectedText;
            OcrDiagnostics.saveRawText(context, diagnosticText.toString());
            recycle(source);
            mainHandler.post(() -> {
                if (!isStale(requestGeneration)) {
                    listener.onSuccess(new ArrayList<>(matches), finalTextWasDetected);
                }
            });
        });
    }

    static List<Rect> createColumnRegions(int width, int height) {
        List<Rect> result = new ArrayList<>();
        result.add(new Rect(0, 0, width, height));
        for (int rowCount : ROW_GRIDS) {
            int baseHeight = (int) Math.ceil(height / (double) rowCount);
            int overlap = Math.max(1, Math.round(baseHeight * VERTICAL_OVERLAP));
            for (int row = 0; row < rowCount; row++) {
                int top = Math.max(0, row * baseHeight - overlap);
                int bottom = Math.min(height, (row + 1) * baseHeight + overlap);
                result.add(new Rect(0, top, width, bottom));
            }
        }
        return result;
    }

    private static Bitmap createPassBitmap(Bitmap source, Pass pass) {
        Bitmap cropped = Bitmap.createBitmap(
                source,
                pass.region.left,
                pass.region.top,
                pass.region.width(),
                pass.region.height());
        Bitmap scaled = scaleForRecognition(cropped);
        if (scaled != cropped) {
            recycle(cropped);
        }
        if (pass.enhancement == Enhancement.ORIGINAL) {
            return scaled;
        }
        Bitmap enhanced = pass.enhancement == Enhancement.THRESHOLD
                ? createThresholdBitmap(scaled)
                : createContrastBitmap(scaled);
        recycle(scaled);
        return enhanced;
    }

    private static Bitmap scaleForRecognition(Bitmap source) {
        float enlarge = Math.max(1f, TARGET_STRIP_WIDTH / (float) source.getWidth());
        float limit = MAX_PASS_DIMENSION
                / (float) Math.max(source.getWidth(), source.getHeight());
        float scale = Math.min(enlarge, limit);
        if (Math.abs(scale - 1f) <= 0.01f) {
            return source;
        }
        return Bitmap.createScaledBitmap(
                source,
                Math.round(source.getWidth() * scale),
                Math.round(source.getHeight() * scale),
                true);
    }

    private static Bitmap createContrastBitmap(Bitmap source) {
        Bitmap result = Bitmap.createBitmap(
                source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        ColorMatrix grayscale = new ColorMatrix();
        grayscale.setSaturation(0f);
        float contrast = 1.65f;
        float translation = (-0.5f * contrast + 0.5f) * 255f;
        ColorMatrix contrastMatrix = new ColorMatrix(new float[]{
                contrast, 0, 0, 0, translation,
                0, contrast, 0, 0, translation,
                0, 0, contrast, 0, translation,
                0, 0, 0, 1, 0
        });
        grayscale.postConcat(contrastMatrix);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setColorFilter(new ColorMatrixColorFilter(grayscale));
        canvas.drawBitmap(source, 0f, 0f, paint);
        return result;
    }

    private static Bitmap createThresholdBitmap(Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int[] pixels = new int[width * height];
        source.getPixels(pixels, 0, width, 0, 0, width, height);
        int[] histogram = new int[256];
        for (int pixel : pixels) {
            int gray = (Color.red(pixel) * 30
                    + Color.green(pixel) * 59
                    + Color.blue(pixel) * 11) / 100;
            histogram[gray]++;
        }
        int threshold = otsuThreshold(histogram, pixels.length);
        for (int index = 0; index < pixels.length; index++) {
            int pixel = pixels[index];
            int gray = (Color.red(pixel) * 30
                    + Color.green(pixel) * 59
                    + Color.blue(pixel) * 11) / 100;
            pixels[index] = gray > threshold ? Color.WHITE : Color.BLACK;
        }
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        result.setPixels(pixels, 0, width, 0, 0, width, height);
        return result;
    }

    private static int otsuThreshold(int[] histogram, int totalPixels) {
        long weightedSum = 0;
        for (int value = 0; value < histogram.length; value++) {
            weightedSum += (long) value * histogram[value];
        }
        long backgroundSum = 0;
        int backgroundWeight = 0;
        double bestVariance = -1;
        int bestThreshold = 160;
        for (int value = 0; value < histogram.length; value++) {
            backgroundWeight += histogram[value];
            if (backgroundWeight == 0) continue;
            int foregroundWeight = totalPixels - backgroundWeight;
            if (foregroundWeight == 0) break;
            backgroundSum += (long) value * histogram[value];
            double backgroundMean = backgroundSum / (double) backgroundWeight;
            double foregroundMean = (weightedSum - backgroundSum) / (double) foregroundWeight;
            double difference = backgroundMean - foregroundMean;
            double variance = (double) backgroundWeight * foregroundWeight * difference * difference;
            if (variance > bestVariance) {
                bestVariance = variance;
                bestThreshold = value;
            }
        }
        return bestThreshold;
    }

    private static void appendDiagnostic(
            StringBuilder target,
            String passName,
            String value) {
        target.append("\n===== ")
                .append(passName)
                .append(" =====\n")
                .append(value == null ? "" : value)
                .append('\n');
    }

    private boolean isStale(int requestGeneration) {
        return closed || requestGeneration != generation;
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }

    public void close() {
        closed = true;
        generation++;
        if (handwritingEngine != null) {
            try {
                handwritingEngine.close();
            } catch (Exception ignored) {
                // No hay trabajo adicional al cerrar la actividad.
            }
            handwritingEngine = null;
        }
        worker.shutdown();
    }

    private enum Enhancement { ORIGINAL, CONTRAST, THRESHOLD }

    private static final class Pass {
        private final Rect region;
        private final Enhancement enhancement;

        private Pass(Rect region, Enhancement enhancement) {
            this.region = region;
            this.enhancement = enhancement;
        }
    }
}
