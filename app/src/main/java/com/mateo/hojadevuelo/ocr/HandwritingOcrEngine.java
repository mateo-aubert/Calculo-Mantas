package com.mateo.hojadevuelo.ocr;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reconocedor PP-OCRv6 local, limitado a las matrículas válidas del catálogo. */
final class HandwritingOcrEngine implements AutoCloseable {
    private static final String MODEL_ASSET = "ppocrv6_tiny_rec.onnx";
    private static final int INPUT_HEIGHT = 48;
    private static final int MIN_INPUT_WIDTH = 32;
    private static final int MAX_INPUT_WIDTH = 640;
    private static final int BLANK_INDEX = 0;
    private static final double MINIMUM_LOG_PROBABILITY = -16.0;
    private static final double MINIMUM_SCORE_MARGIN = 0.30;

    private static final Map<Character, Integer> CHARACTER_INDICES = createCharacterIndices();

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String inputName;
    private final List<String> compactCatalog;
    private final Map<String, String> formattedByCompact;

    HandwritingOcrEngine(Context context, List<String> registrations)
            throws IOException, OrtException {
        environment = OrtEnvironment.getEnvironment();
        byte[] model = readAsset(context, MODEL_ASSET);
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        session = environment.createSession(model, options);
        options.close();
        inputName = session.getInputNames().iterator().next();

        compactCatalog = new ArrayList<>();
        formattedByCompact = new LinkedHashMap<>();
        for (String registration : registrations) {
            String compact = compact(registration);
            compactCatalog.add(compact);
            formattedByCompact.put(compact, format(compact));
        }
    }

    List<String> recognize(Bitmap column) throws OrtException {
        List<Rect> rows = findTextRows(column);
        List<String> result = new ArrayList<>();
        for (Rect row : rows) {
            Bitmap rowBitmap = Bitmap.createBitmap(
                    column, row.left, row.top, row.width(), row.height());
            try {
                Candidate winner = recognizeRow(rowBitmap);
                if (winner != null) {
                    result.add(formattedByCompact.get(winner.registration));
                }
            } finally {
                rowBitmap.recycle();
            }
        }
        return result;
    }

    private Candidate recognizeRow(Bitmap row) throws OrtException {
        PreparedInput prepared = prepare(row);
        long[] shape = {1, 3, INPUT_HEIGHT, prepared.width};
        try (OnnxTensor input = OnnxTensor.createTensor(
                environment, FloatBuffer.wrap(prepared.values), shape);
             OrtSession.Result output = session.run(
                     Collections.singletonMap(inputName, input))) {
            Object value = output.get(0).getValue();
            if (!(value instanceof float[][][])) {
                throw new OrtException("Salida PP-OCRv6 inesperada.");
            }
            float[][] probabilities = ((float[][][]) value)[0];
            List<Candidate> candidates = new ArrayList<>(compactCatalog.size());
            for (String registration : compactCatalog) {
                candidates.add(new Candidate(
                        registration,
                        ctcLogProbability(probabilities, registration)));
            }
            candidates.sort(Comparator.comparingDouble(candidate -> -candidate.score));
            Candidate best = candidates.get(0);
            double margin = best.score - candidates.get(1).score;
            return best.score >= MINIMUM_LOG_PROBABILITY && margin >= MINIMUM_SCORE_MARGIN
                    ? best
                    : null;
        }
    }

    static List<Rect> findTextRows(Bitmap image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = new int[width * height];
        image.getPixels(pixels, 0, width, 0, 0, width, height);

        float[] projection = new float[height];
        for (int y = 0; y < height; y++) {
            int count = 0;
            int offset = y * width;
            for (int x = 0; x < width; x++) {
                int pixel = pixels[offset + x];
                int gray = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3;
                if (gray < 215) count++;
            }
            projection[y] = count > width * 0.72f ? 0f : count;
        }

        float threshold = Math.max(3f, width * 0.012f);
        boolean[] active = new boolean[height];
        for (int y = 0; y < height; y++) {
            float sum = 0f;
            int samples = 0;
            for (int sample = Math.max(0, y - 4); sample <= Math.min(height - 1, y + 4); sample++) {
                sum += projection[sample];
                samples++;
            }
            active[y] = sum / samples > threshold;
        }

        List<int[]> runs = new ArrayList<>();
        int start = -1;
        for (int y = 0; y < height; y++) {
            if (active[y] && start < 0) {
                start = y;
            } else if (!active[y] && start >= 0) {
                if (y - start >= 8) runs.add(new int[]{start, y});
                start = -1;
            }
        }
        if (start >= 0) runs.add(new int[]{start, height});

        List<int[]> merged = new ArrayList<>();
        for (int[] run : runs) {
            if (!merged.isEmpty() && run[0] - merged.get(merged.size() - 1)[1] < 5) {
                merged.get(merged.size() - 1)[1] = run[1];
            } else {
                merged.add(run.clone());
            }
        }

        int[] heights = new int[merged.size()];
        for (int index = 0; index < merged.size(); index++) {
            heights[index] = merged.get(index)[1] - merged.get(index)[0];
        }
        Arrays.sort(heights);
        int typicalHeight = heights.length == 0 ? 1 : heights[heights.length / 2];

        List<Rect> result = new ArrayList<>();
        for (int[] run : merged) {
            int runHeight = run[1] - run[0];
            int parts = Math.max(1, Math.round(runHeight / (float) typicalHeight));
            if (parts == 1 || runHeight < typicalHeight * 1.55f) parts = 1;
            float partHeight = runHeight / (float) parts;
            for (int part = 0; part < parts; part++) {
                int top = Math.max(0, Math.round(run[0] + part * partHeight) - 10);
                int bottom = Math.min(
                        height,
                        Math.round(run[0] + (part + 1) * partHeight) + 10);
                if (bottom - top >= 28) {
                    result.add(new Rect(0, top, width, bottom));
                }
            }
        }
        return result;
    }

    private static PreparedInput prepare(Bitmap row) {
        Rect inkBounds = findHorizontalInkBounds(row);
        Bitmap cropped = Bitmap.createBitmap(
                row, inkBounds.left, inkBounds.top, inkBounds.width(), inkBounds.height());
        int targetWidth = Math.max(
                MIN_INPUT_WIDTH,
                Math.min(MAX_INPUT_WIDTH, Math.round(cropped.getWidth() * INPUT_HEIGHT
                        / (float) cropped.getHeight())));
        Bitmap resized = Bitmap.createBitmap(targetWidth, INPUT_HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(resized);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(cropped, null, new Rect(0, 0, targetWidth, INPUT_HEIGHT), paint);

        int[] pixels = new int[targetWidth * INPUT_HEIGHT];
        resized.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, INPUT_HEIGHT);
        float[] values = new float[3 * targetWidth * INPUT_HEIGHT];
        int plane = targetWidth * INPUT_HEIGHT;
        for (int index = 0; index < pixels.length; index++) {
            int pixel = pixels[index];
            // El modelo oficial recibe BGR, CHW y valores normalizados a [-1, 1].
            values[index] = Color.blue(pixel) / 127.5f - 1f;
            values[plane + index] = Color.green(pixel) / 127.5f - 1f;
            values[plane * 2 + index] = Color.red(pixel) / 127.5f - 1f;
        }
        cropped.recycle();
        resized.recycle();
        return new PreparedInput(targetWidth, values);
    }

    private static Rect findHorizontalInkBounds(Bitmap row) {
        int width = row.getWidth();
        int height = row.getHeight();
        int[] pixels = new int[width * height];
        row.getPixels(pixels, 0, width, 0, 0, width, height);
        int first = width;
        int last = -1;
        for (int x = 0; x < width; x++) {
            int ink = 0;
            for (int y = 0; y < height; y++) {
                int pixel = pixels[y * width + x];
                int gray = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3;
                if (gray < 225) ink++;
            }
            if (ink > 2) {
                first = Math.min(first, x);
                last = x;
            }
        }
        if (last < first) return new Rect(0, 0, width, height);
        return new Rect(Math.max(0, first - 12), 0, Math.min(width, last + 13), height);
    }

    private static double ctcLogProbability(float[][] probabilities, String candidate) {
        int[] extended = new int[candidate.length() * 2 + 1];
        for (int index = 0; index < candidate.length(); index++) {
            Integer characterIndex = CHARACTER_INDICES.get(candidate.charAt(index));
            if (characterIndex == null) return Double.NEGATIVE_INFINITY;
            extended[index * 2 + 1] = characterIndex;
        }

        double[] previous = new double[extended.length];
        Arrays.fill(previous, Double.NEGATIVE_INFINITY);
        previous[0] = safeLog(probabilities[0][BLANK_INDEX]);
        if (extended.length > 1) {
            previous[1] = safeLog(probabilities[0][extended[1]]);
        }

        for (int time = 1; time < probabilities.length; time++) {
            double[] current = new double[extended.length];
            Arrays.fill(current, Double.NEGATIVE_INFINITY);
            for (int state = 0; state < extended.length; state++) {
                double total = previous[state];
                if (state > 0) total = logAdd(total, previous[state - 1]);
                if (state > 1
                        && extended[state] != BLANK_INDEX
                        && extended[state] != extended[state - 2]) {
                    total = logAdd(total, previous[state - 2]);
                }
                current[state] = total + safeLog(probabilities[time][extended[state]]);
            }
            previous = current;
        }
        return logAdd(previous[previous.length - 1], previous[previous.length - 2]);
    }

    private static double safeLog(float value) {
        return Math.log(Math.max(1e-12, value));
    }

    private static double logAdd(double left, double right) {
        if (Double.isInfinite(left)) return right;
        if (Double.isInfinite(right)) return left;
        double maximum = Math.max(left, right);
        return maximum + Math.log(Math.exp(left - maximum) + Math.exp(right - maximum));
    }

    private static Map<Character, Integer> createCharacterIndices() {
        Map<Character, Integer> result = new LinkedHashMap<>();
        for (char digit = '0'; digit <= '9'; digit++) result.put(digit, 33 + digit - '0');
        for (char letter = 'A'; letter <= 'Z'; letter++) result.put(letter, 43 + letter - 'A');
        result.put('-', 13);
        return result;
    }

    private static byte[] readAsset(Context context, String assetName) throws IOException {
        try (InputStream input = context.getAssets().open(assetName);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return output.toByteArray();
        }
    }

    private static String compact(String value) {
        return value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private static String format(String compact) {
        return compact.length() == 5
                ? compact.substring(0, 2) + "-" + compact.substring(2)
                : compact;
    }

    @Override
    public void close() throws OrtException {
        session.close();
    }

    private static final class PreparedInput {
        private final int width;
        private final float[] values;

        private PreparedInput(int width, float[] values) {
            this.width = width;
            this.values = values;
        }
    }

    private static final class Candidate {
        private final String registration;
        private final double score;

        private Candidate(String registration, double score) {
            this.registration = registration;
            this.score = score;
        }
    }
}
