package com.mateo.hojadevuelo.ocr;

import android.content.Context;
import android.graphics.Bitmap;

import com.googlecode.tesseract.android.TessBaseAPI;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

final class TesseractOcrEngine implements AutoCloseable {
    private static final String LANGUAGE = "eng";
    private static final String MODEL_ASSET = "tessdata/eng.traineddata";
    private static final String MODEL_FILE_NAME = "eng.traineddata";
    private static final String CHARACTER_WHITELIST = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-";

    private TessBaseAPI api;

    void initialize(Context context) throws IOException {
        if (api != null) {
            return;
        }

        File dataRoot = new File(context.getFilesDir(), "tesseract");
        File tessdataDirectory = new File(dataRoot, "tessdata");
        if (!tessdataDirectory.exists() && !tessdataDirectory.mkdirs()) {
            throw new IOException("No se pudo crear el directorio de datos OCR.");
        }

        File modelFile = new File(tessdataDirectory, MODEL_FILE_NAME);
        copyModelIfNeeded(context, modelFile);

        TessBaseAPI newApi = new TessBaseAPI();
        if (!newApi.init(dataRoot.getAbsolutePath(), LANGUAGE)) {
            newApi.recycle();
            throw new IOException("No se pudo inicializar Tesseract.");
        }

        newApi.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT);
        newApi.setVariable("tessedit_char_whitelist", CHARACTER_WHITELIST);
        newApi.setVariable("user_defined_dpi", "300");
        newApi.setVariable("tessedit_enable_dict_correction", "0");
        api = newApi;
    }

    String recognize(Bitmap bitmap) {
        if (api == null) {
            throw new IllegalStateException("Tesseract no está inicializado.");
        }
        api.setImage(bitmap);
        try {
            String text = api.getUTF8Text();
            return text == null ? "" : text;
        } finally {
            api.clear();
        }
    }

    private static void copyModelIfNeeded(Context context, File destination) throws IOException {
        long assetSize;
        try (InputStream input = context.getAssets().open(MODEL_ASSET)) {
            assetSize = input.available();
        }

        if (destination.isFile() && destination.length() == assetSize) {
            return;
        }

        try (InputStream input = context.getAssets().open(MODEL_ASSET);
             OutputStream output = new FileOutputStream(destination, false)) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    @Override
    public void close() {
        if (api != null) {
            api.recycle();
            api = null;
        }
    }
}
