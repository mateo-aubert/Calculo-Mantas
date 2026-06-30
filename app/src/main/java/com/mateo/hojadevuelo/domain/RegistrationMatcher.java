package com.mateo.hojadevuelo.domain;

import com.mateo.hojadevuelo.data.AircraftCatalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RegistrationMatcher {
    private static final String SEPARATOR = "[\\s\\-–—_:./|]*";
    private static final Pattern PREFIXED_CANDIDATE = Pattern.compile(
            "(?i)(?<![A-Z0-9])E" + SEPARATOR + "[C0]" + SEPARATOR
                    + "([A-Z0-9])" + SEPARATOR
                    + "([A-Z0-9])" + SEPARATOR
                    + "([A-Z0-9])(?![A-Z0-9])");
    private static final Pattern GENERIC_FIVE_CHARACTER_TOKEN = Pattern.compile(
            "(?i)(?<![A-Z0-9])"
                    + "([A-Z0-9])" + SEPARATOR
                    + "([A-Z0-9])" + SEPARATOR
                    + "([A-Z0-9])" + SEPARATOR
                    + "([A-Z0-9])" + SEPARATOR
                    + "([A-Z0-9])(?![A-Z0-9])");

    private final List<String> knownRegistrations;
    private final Map<String, String> registrationByCompactValue;
    private final List<SuffixRule> suffixRules;

    public RegistrationMatcher(List<String> registrations) {
        knownRegistrations = new ArrayList<>(registrations);
        registrationByCompactValue = new LinkedHashMap<>();
        suffixRules = new ArrayList<>();

        for (String registration : registrations) {
            String normalized = AircraftCatalog.normalizeRegistration(registration);
            String compactRegistration = compact(normalized);
            registrationByCompactValue.put(compactRegistration, normalized);

            String suffix = compactRegistration.substring(2);
            Pattern suffixPattern = Pattern.compile(
                    "(?i)(?<![A-Z0-9])"
                            + Pattern.quote(String.valueOf(suffix.charAt(0))) + SEPARATOR
                            + Pattern.quote(String.valueOf(suffix.charAt(1))) + SEPARATOR
                            + Pattern.quote(String.valueOf(suffix.charAt(2)))
                            + "(?![A-Z0-9])");
            suffixRules.add(new SuffixRule(normalized, suffixPattern));
        }
    }

    public List<String> findRegistrations(String recognizedText) {
        Set<String> matches = new LinkedHashSet<>();
        if (recognizedText == null || recognizedText.isBlank()) {
            return new ArrayList<>();
        }

        String upperText = recognizedText.toUpperCase(Locale.ROOT);
        Matcher prefixedMatcher = PREFIXED_CANDIDATE.matcher(upperText);
        while (prefixedMatcher.find()) {
            addExactOrUniqueFuzzyMatch(
                    "EC"
                            + prefixedMatcher.group(1)
                            + prefixedMatcher.group(2)
                            + prefixedMatcher.group(3),
                    matches);
        }

        // Recupera casos como FC-NFM cuando el OCR estropea una letra del prefijo.
        Matcher genericMatcher = GENERIC_FIVE_CHARACTER_TOKEN.matcher(upperText);
        while (genericMatcher.find()) {
            StringBuilder candidate = new StringBuilder(5);
            for (int group = 1; group <= 5; group++) {
                candidate.append(genericMatcher.group(group));
            }
            addExactOrUniqueFuzzyMatch(candidate.toString(), matches);
        }

        // Todas las terminaciones del catálogo son únicas. Esto permite rescatar NFM
        // aunque el OCR no consiga leer EC, sin aceptar matrículas ajenas al catálogo.
        for (SuffixRule rule : suffixRules) {
            if (rule.pattern.matcher(upperText).find()) {
                matches.add(rule.registration);
            }
        }

        // Respaldo para EC-NFM, ECNFM o letras separadas en distintos fragmentos.
        String compactText = compact(recognizedText);
        for (String known : knownRegistrations) {
            if (compactText.contains(compact(known))) {
                matches.add(known);
            }
        }
        return new ArrayList<>(matches);
    }

    private void addExactOrUniqueFuzzyMatch(String candidate, Set<String> matches) {
        String exact = registrationByCompactValue.get(candidate);
        if (exact != null) {
            matches.add(exact);
            return;
        }
        String fuzzy = uniqueNearestMatch(candidate);
        if (fuzzy != null) {
            matches.add(fuzzy);
        }
    }

    private String uniqueNearestMatch(String candidate) {
        String winner = null;
        int bestDistance = Integer.MAX_VALUE;
        boolean tied = false;

        for (Map.Entry<String, String> entry : registrationByCompactValue.entrySet()) {
            int distance = levenshtein(candidate, entry.getKey());
            if (distance < bestDistance) {
                bestDistance = distance;
                winner = entry.getValue();
                tied = false;
            } else if (distance == bestDistance) {
                tied = true;
            }
        }
        return bestDistance == 1 && !tied ? winner : null;
    }

    private static String compact(String value) {
        return value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    static int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        for (int column = 0; column <= right.length(); column++) {
            previous[column] = column;
        }

        for (int row = 1; row <= left.length(); row++) {
            int[] current = new int[right.length() + 1];
            current[0] = row;
            for (int column = 1; column <= right.length(); column++) {
                int substitution = previous[column - 1]
                        + (left.charAt(row - 1) == right.charAt(column - 1) ? 0 : 1);
                current[column] = Math.min(
                        Math.min(current[column - 1] + 1, previous[column] + 1),
                        substitution);
            }
            previous = current;
        }
        return previous[right.length()];
    }

    private static final class SuffixRule {
        private final String registration;
        private final Pattern pattern;

        private SuffixRule(String registration, Pattern pattern) {
            this.registration = registration;
            this.pattern = pattern;
        }
    }
}
