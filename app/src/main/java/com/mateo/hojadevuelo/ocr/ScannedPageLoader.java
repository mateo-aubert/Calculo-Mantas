package com.mateo.hojadevuelo.ocr;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;

import androidx.exifinterface.media.ExifInterface;

import java.io.IOException;
import java.io.InputStream;

public final class ScannedPageLoader {
    private static final int MAX_IMAGE_DIMENSION = 5600;

    private ScannedPageLoader() {
    }

    /** Debe ejecutarse fuera del hilo de interfaz. */
    public static Bitmap load(Context context, Uri uri) throws IOException {
        int orientation = readOrientation(context, uri);
        Bitmap decoded = decode(context, uri);
        return applyOrientation(decoded, orientation);
    }

    private static int readOrientation(Context context, Uri uri) throws IOException {
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

    private static Bitmap decode(Context context, Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new IOException("No se pudo abrir la imagen escaneada.");
            }
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("La imagen no tiene un tamaño válido.");
        }

        int sampleSize = 1;
        int largestDimension = Math.max(bounds.outWidth, bounds.outHeight);
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
            Bitmap result = BitmapFactory.decodeStream(input, null, options);
            if (result == null) {
                throw new IOException("No se pudo decodificar la imagen escaneada.");
            }
            return result;
        }
    }

    private static Bitmap applyOrientation(Bitmap source, int orientation) {
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
                source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
        if (oriented != source) {
            source.recycle();
        }
        return oriented;
    }
}
