package com.mateo.hojadevuelo.domain;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RegistrationMatcherTest {
    private RegistrationMatcher matcher;

    @Before
    public void setUp() {
        matcher = new RegistrationMatcher(Arrays.asList(
                "EC-MIG", "EC-MIH", "EC-NFM", "EC-NXA", "EC-NVZ", "EC-NZG", "EC-OMB"));
    }

    @Test
    public void findsRegistrationsWithDifferentSeparators() {
        List<String> result = matcher.findRegistrations(
                "Salida EC-MIG  Llegada E C – N X A  Reserva EC/NVZ");

        assertEquals(Arrays.asList("EC-MIG", "EC-NXA", "EC-NVZ"), result);
    }

    @Test
    public void removesDuplicatesButPreservesReadingOrder() {
        List<String> result = matcher.findRegistrations(
                "EC-OMB\nEC-MIG\nEC-OMB");

        assertEquals(Arrays.asList("EC-OMB", "EC-MIG"), result);
    }

    @Test
    public void acceptsOnlyAnUnambiguousSingleCharacterOcrError() {
        assertEquals(
                Arrays.asList("EC-OMB"),
                matcher.findRegistrations("EC-0MB"));
        assertTrue(matcher.findRegistrations("EC-XXX").isEmpty());
    }

    @Test
    public void ignoresTextWithoutKnownRegistrations() {
        assertTrue(matcher.findRegistrations("MAD  IB3166  12:45").isEmpty());
    }

    @Test
    public void acceptsDashedAndCompactRegistrationFormats() {
        assertEquals(
                Arrays.asList("EC-NFM"),
                matcher.findRegistrations("EC-NFM / ECNFM"));
    }

    @Test
    public void recoversUniqueSuffixWhenPrefixIsDamagedOrMissing() {
        assertEquals(
                Arrays.asList("EC-NFM"),
                matcher.findRegistrations("FC-NFM"));
        assertEquals(
                Arrays.asList("EC-NFM"),
                matcher.findRegistrations("Avión NFM"));
    }
}
