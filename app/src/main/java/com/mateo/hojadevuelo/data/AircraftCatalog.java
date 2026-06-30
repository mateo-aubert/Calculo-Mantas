package com.mateo.hojadevuelo.data;

import android.content.Context;

import com.mateo.hojadevuelo.model.Aircraft;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AircraftCatalog {
    public static final String CATEGORY_800 = "800";
    public static final String CATEGORY_900 = "900";
    public static final String CATEGORY_PREMIUM = "Premium";
    public static final String CATEGORY_UNKNOWN = "No encontrada";

    private static final Set<String> PREMIUM_REGISTRATIONS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("EC-NXA", "EC-NVZ", "EC-NZG")));
    private static final Pattern REGISTRATION_PATTERN =
            Pattern.compile("^EC[-\\s]?([A-Z0-9]{3})$");

    private final Map<String, Aircraft> aircraftByRegistration;

    private AircraftCatalog(Map<String, Aircraft> aircraftByRegistration) {
        this.aircraftByRegistration = Collections.unmodifiableMap(aircraftByRegistration);
    }

    public static AircraftCatalog load(Context context) {
        try (InputStream input = context.getAssets().open("matriculas_modelos.json");
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder json = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                json.append(line);
            }

            JSONArray array = new JSONArray(json.toString());
            Map<String, Aircraft> entries = new LinkedHashMap<>();
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.getJSONObject(index);
                String registration = normalizeRegistration(item.getString("matricula"));
                String model = item.getString("modelo").trim();
                if (entries.put(registration, new Aircraft(registration, model)) != null) {
                    throw new IllegalStateException("Matrícula duplicada: " + registration);
                }
            }
            return new AircraftCatalog(entries);
        } catch (IOException | JSONException error) {
            throw new IllegalStateException("No se pudo cargar matriculas_modelos.json", error);
        }
    }

    public List<String> getAllRegistrations() {
        return new ArrayList<>(aircraftByRegistration.keySet());
    }

    public boolean contains(String registration) {
        return aircraftByRegistration.containsKey(normalizeRegistration(registration));
    }

    public String categoryFor(String registration) {
        String normalized = normalizeRegistration(registration);
        if (PREMIUM_REGISTRATIONS.contains(normalized)) {
            return CATEGORY_PREMIUM;
        }

        Aircraft aircraft = aircraftByRegistration.get(normalized);
        if (aircraft == null) {
            return CATEGORY_UNKNOWN;
        }
        if ("787-8".equals(aircraft.getModel())) {
            return CATEGORY_800;
        }
        if ("787-9".equals(aircraft.getModel())) {
            return CATEGORY_900;
        }
        return CATEGORY_UNKNOWN;
    }

    public static String normalizeRegistration(String value) {
        if (value == null) {
            return "";
        }
        String upper = value.trim()
                .toUpperCase(Locale.ROOT)
                .replace('–', '-')
                .replace('—', '-');
        Matcher matcher = REGISTRATION_PATTERN.matcher(upper);
        if (matcher.matches()) {
            return "EC-" + matcher.group(1);
        }
        return upper;
    }
}
