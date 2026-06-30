package com.mateo.hojadevuelo.ocr;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.exifinterface.media.ExifInterface;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognizer;
import com.mateo.hojadevuelo.domain.RegistrationMatcher;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DocumentOcrProcessor {
    public interface Listener {
        void onProgress(int completedPasses, int totalPasses);
        void onSuccess(List<String> registrations, boolean textWasDetected);
        void onError(Exception error);
    }

    private static final int MAX_IMAGE_DIMENSION = 5200;
    private static final int GRID_COLUMNS = 2;
    private static final int GRID_ROWS = 4;
    private static final float OVERLAP_RATIO = 0.06f;

    private final TextRecognizer recognizer;
    private final RegistrationMatcher matcher;
    private final ExecutorService decodeExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int generation;
    private boolean closed;

    public DocumentOcrProcessor(TextRecognizer recognizer, RegistrationMatcher matcher) {
        this.recognizer = recognizer;
        this.matcher = matcher;
    }

    public void process(Context context, Uri imageUri, Listener listener) {
        int requestGeneration = ++generation;
        decodeExecutor.execute(() -> {
            try {
                int exifOrientation = readExifOrientation(context, imageUri);
                Bitmap decoded = decodeForOcr(context, imageUri);
                Bitmap source = applyExifOrientation(decoded, exifOrientation);
                List<OcrPass> passes = createPasses(source.getWidth(), source.getHeight());
                mainHandler.post(() -> {
                    if (isStale(requestGeneration)) {
                        source.recycle();
                        return;
                    }
                    processPass(
                            context.getApplicationContext(),
                            source,
                            passes,
                            0,
                            requestGeneration,
                            new LinkedHashSet<>(),
                            0,
                            false,
                            listener,
                            null);
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (!isStale(requestGeneration)) {
                        listener.onError(error);
                    }
                });
            }
        });
    }

    private void processPass(
            Context context,
            Bitmap source,
            List<OcrPass> passes,
            int index,
            int requestGeneration,
            Set<String> matches,
            int successfulPasses,
            boolean textWasDetected,
            Listener listener,
            Exception lastError) {
        if (isStale(requestGeneration)) {
            source.recycle();
            return;
        }
        if (index >= passes.size()) {
            if (successfulPasses == 0 && lastError != null) {
                source.recycle();
                listener.onError(lastError);
            } else {
                processWithTesseract(
                        context,
                        source,
                        passes.size(),
                        requestGeneration,
                        matches,
                        textWasDetected,
                        listener);
            }
            return;
        }

        OcrPass pass = passes.get(index);
        Bitmap passBitmap = createPassBitmap(source, pass);
        final Exception[] passError = {lastError};
        final int[] completedSuccessfulPasses = {successfulPasses};
        final boolean[] detectedText = {textWasDetected};

        recognizer.process(InputImage.fromBitmap(passBitmap, 0))
                .addOnSuccessListener(text -> {
                    String recognizedText = text.getText();
                    if (recognizedText != null && !recognizedText.trim().isEmpty()) {
                        detectedText[0] = true;
                    }
                    matches.addAll(matcher.findRegistrations(recognizedText));
                    completedSuccessfulPasses[0]++;
                })
                .addOnFailureListener(error -> passError[0] = error)
                .addOnCompleteListener(task -> {
                    if (passBitmap != source) {
                        passBitmap.recycle();
                    }
                    int completed = index + 1;
                    int totalPasses = passes.size()
                            + createRegions(source.getWidth(), source.getHeight()).size();
                    listener.onProgress(completed, totalPasses);
                    processPass(
                            context,
                            source,
                            passes,
                            completed,
                            requestGeneration,
                            matches,
                            completedSuccessfulPasses[0],
                            detectedText[0],
                            listener,
                            passError[0]);
                });
    }

    private void processWithTesseract(
            Context context,
            Bitmap source,
            int completedMlKitPasses,
            int requestGeneration,
            Set<String> matches,
            boolean textWasDetected,
            Listener listener) {
        List<Rect> regions = createRegions(source.getWidth(), source.getHeight());
        int totalPasses = completedMlKitPasses + regions.size();

        decodeExecutor.execute(() -> {
            boolean detectedText = textWasDetected;
            try (TesseractOcrEngine tesseract = new TesseractOcrEngine()) {
                tesseract.initialize(context);
                for (int index = 0; index < regions.size(); index++) {
                    if (isStale(requestGeneration)) {
                        source.recycle();
                        return;
                    }

                    OcrPass pass = new OcrPass(regions.get(index), true);
                    Bitmap passBitmap = createPassBitmap(source, pass);
                    try {
                        String recognizedText = tesseract.recognize(passBitmap);
                        if (!recognizedText.trim().isEmpty()) {
                            detectedText = true;
                        }
                        matches.addAll(matcher.findRegistrations(recognizedText));
                    } finally {
                        if (passBitmap != source) {
                            passBitmap.recycle();
                        }
                    }

                    int completed = completedMlKitPasses + index + 1;
                    mainHandler.post(() -> {
                        if (!isStale(requestGeneration)) {
                            listener.onProgress(completed, totalPasses);
                        }
                    });
                }
            } catch (Exception ignored) {
                // Tesseract es un segundo motor de respaldo. Si falla, se conservan
                // las coincidencias obtenidas por ML Kit.
            }

            boolean finalTextWasDetected = detectedText;
            source.recycle();
            mainHandler.post(() -> {
                if (!isStale(requestGeneration)) {
                    listener.onSuccess(
                            new ArrayList<>(matches),
                            finalTextWasDetected);
                }
            });
        });
    }

    private static Bitmap createPassBitmap(Bitmap source, OcrPass pass) {
        boolean fullImage = isFullImage(pass.region, source);
        Bitmap cropped = fullImage
                ? source
                : Bitmap.createBitmap(
                        source,
                        pass.region.left,
                        pass.region.top,
                        pass.region.width(),
                        pass.region.height());

        if (!pass.highContrast) {
            return cropped;
        }

        Bitmap enhanced = createHighContrastBitmap(cropped);
        if (cropped != source) {
            cropped.recycle();
        }
        return enhanced;
    }

    private static Bitmap createHighContrastBitmap(Bitmap source) {
        Bitmap result = Bitmap.createBitmap(
                source.getWidth(),
                source.getHeight(),
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);

        ColorMatrix grayscale = new ColorMatrix();
        grayscale.setSaturation(0f);

        float contrast = 1.45f;
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
        canvas.drawBitmap(source, 0, 0, paint);
        return result;
    }

    private boolean isStale(int requestGeneration) {
        return closed || requestGeneration != generation;
    }

    private static boolean isFullImage(Rect region, Bitmap source) {
        return region.left == 0
                && region.top == 0
                && region.right == source.getWidth()
                && region.bottom == source.getHeight();
    }

    private static int readExifOrientation(Context context, Uri uri) throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new IOException("No se pudo leer la orientación de la imagen.");
            }
            ExifInterface exif = new ExifInterface(input);
            return exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
        }
    }

    private static Bitmap applyExifOrientation(Bitmap source, int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.setRotate(180f);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.setScale(1f, -1f);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.setRotate(90f);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(-90f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.setRotate(270f);
                break;
            default:
                return source;
        }

        Bitmap oriented = Bitmap.createBitmap(
                source,
                0,
                0,
                source.getWidth(),
                source.getHeight(),
                matrix,
                true);
        if (oriented != source) {
            source.recycle();
        }
        return oriented;
    }

    private static Bitmap decodeForOcr(Context context, Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new IOException("No se pudo abrir la imagen escaneada.");
            }
            BitmapFactory.decodeStream(input, null, bounds);
        }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("La imagen escaneada no tiene un tamaño válido.");
        }

        int largestDimension = Math.max(bounds.outWidth, bounds.outHeight);
        int sampleSize = 1;
        while (largestDimension / sampleSize > MAX_IMAGE_DIMENSION) {
            sampleSize *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new IOException("No se pudo volver a abrir la imagen escaneada.");
            }
            Bitmap bitmap = BitmapFactory.decodeStream(input, null, options);
            if (bitmap == null) {
                throw new IOException("No se pudo decodificar la imagen escaneada.");
            }
            return bitmap;
        }
    }

    static List<OcrPass> createPasses(int width, int height) {
        List<Rect> regions = createRegions(width, height);
        List<OcrPass> passes = new ArrayList<>(regions.size() * 2);
        for (Rect region : regions) {
            passes.add(new OcrPass(region, false));
            passes.add(new OcrPass(region, true));
        }
        return passes;
    }

    static List<Rect> createRegions(int width, int height) {
        List<Rect> regions = new ArrayList<>();
        regions.add(new Rect(0, 0, width, height));

        int cellWidth = (int) Math.ceil(width / (double) GRID_COLUMNS);
        int cellHeight = (int) Math.ceil(height / (double) GRID_ROWS);
        int overlapX = Math.max(1, Math.round(width * OVERLAP_RATIO));
        int overlapY = Math.max(1, Math.round(height * OVERLAP_RATIO));

        for (int row = 0; row < GRID_ROWS; row++) {
            for (int column = 0; column < GRID_COLUMNS; column++) {
                int left = Math.max(0, column * cellWidth - overlapX);
                int top = Math.max(0, row * cellHeight - overlapY);
                int right = Math.min(width, (column + 1) * cellWidth + overlapX);
                int bottom = Math.min(height, (row + 1) * cellHeight + overlapY);
                regions.add(new Rect(left, top, right, bottom));
            }
        }
        return regions;
    }

    public void close() {
        closed = true;
        generation++;
        decodeExecutor.shutdown();
    }

    static final class OcrPass {
        private final Rect region;
        private final boolean highContrast;

        private OcrPass(Rect region, boolean highContrast) {
            this.region = region;
            this.highContrast = highContrast;
        }
    }
}
