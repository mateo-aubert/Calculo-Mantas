package com.mateo.hojadevuelo.ocr;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

import androidx.test.platform.app.InstrumentationRegistry;

import com.mateo.hojadevuelo.domain.RegistrationMatcher;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertTrue;

public class TesseractOcrEngineInstrumentedTest {
    @Test
    public void recognizesDashedAndCompactRegistrations() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Bitmap image = Bitmap.createBitmap(1800, 1100, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image);
        canvas.drawColor(Color.WHITE);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        paint.setTextSize(108f);
        paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));

        canvas.drawText("HOJA DE VUELOS", 100, 150, paint);
        canvas.drawText("EC-NFM", 120, 380, paint);
        canvas.drawText("ECNFM", 920, 380, paint);
        canvas.drawText("EC-MIG", 120, 650, paint);
        canvas.drawText("EC-OMB", 920, 650, paint);

        String recognizedText;
        try (TesseractOcrEngine engine = new TesseractOcrEngine()) {
            engine.initialize(context);
            recognizedText = engine.recognize(image);
        } finally {
            image.recycle();
        }

        RegistrationMatcher matcher = new RegistrationMatcher(
                Arrays.asList("EC-NFM", "EC-MIG", "EC-OMB"));
        List<String> matches = matcher.findRegistrations(recognizedText);

        assertTrue("Tesseract no devolvió texto", !recognizedText.trim().isEmpty());
        assertTrue("No reconoció EC-NFM/ECNFM: " + recognizedText, matches.contains("EC-NFM"));
        assertTrue("No reconoció suficientes matrículas: " + recognizedText, matches.size() >= 2);
    }
}
