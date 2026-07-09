package com.mateo.hojadevuelo.data;

import com.mateo.hojadevuelo.domain.BlanketCalculator;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class BlanketCalculatorTest {
    @Test
    public void calculatesCabinAndHoldBlanketsAndRoundsCagesUp() {
        Map<String, String> models = new LinkedHashMap<>();
        models.put("EC-MIG", "787-8");
        models.put("EC-MTI", "787-9");
        models.put("EC-NXA", "787-8");
        models.put("EC-OGY", "787-9");

        AircraftCatalog catalog = AircraftCatalog.fromModels(models);

        BlanketCalculator.Summary summary = BlanketCalculator.calculate(
                catalog,
                Arrays.asList("EC-MIG", "EC-MTI", "EC-NXA", "EC-OGY"));

        assertEquals(4, summary.getAircraftCount());
        assertEquals(242, summary.getTouristBlankets());
        assertEquals(80, summary.getThickBlankets());
        assertEquals(60, summary.getToppers());
        assertEquals(16, summary.getTouristCages());
        assertEquals(10, summary.getThickCages());
        assertEquals(3, summary.getTopperCages());
    }

    @Test
    public void treatsEcOgyAsPremium() {
        Map<String, String> models = new LinkedHashMap<>();
        models.put("EC-OGY", "787-9");

        AircraftCatalog catalog = AircraftCatalog.fromModels(models);

        assertEquals(AircraftCatalog.CATEGORY_PREMIUM, catalog.categoryFor("EC-OGY"));
    }

    @Test
    public void calculatesSingleLoadsForIndependentAircraftAndHoldTracking() {
        Map<String, String> models = new LinkedHashMap<>();
        models.put("EC-MTI", "787-9");
        models.put("EC-NXA", "787-8");

        AircraftCatalog catalog = AircraftCatalog.fromModels(models);

        BlanketCalculator.Summary summary = BlanketCalculator.calculateSingleLoad(
                catalog,
                Arrays.asList("EC-MTI", "EC-NXA"));

        assertEquals(2, summary.getAircraftCount());
        assertEquals(62, summary.getTouristBlankets());
        assertEquals(21, summary.getThickBlankets());
        assertEquals(16, summary.getToppers());
        assertEquals(4, summary.getTouristCages());
        assertEquals(3, summary.getThickCages());
        assertEquals(1, summary.getTopperCages());
    }

    @Test
    public void combinesActiveDeparturesWithRetiredUsedLoads() {
        Map<String, String> models = new LinkedHashMap<>();
        models.put("EC-MTI", "787-9");
        models.put("EC-MOM", "787-8");

        AircraftCatalog catalog = AircraftCatalog.fromModels(models);

        BlanketCalculator.Summary activeDepartures = BlanketCalculator.calculate(
                catalog,
                Arrays.asList("EC-MOM"));
        BlanketCalculator.Summary retiredUsedAircraft = BlanketCalculator.calculateSingleLoad(
                catalog,
                Arrays.asList("EC-MTI"));

        BlanketCalculator.Summary realJourney =
                BlanketCalculator.combine(activeDepartures, retiredUsedAircraft);

        assertEquals(2, realJourney.getAircraftCount());
        assertEquals(90, realJourney.getTouristBlankets());
        assertEquals(29, realJourney.getThickBlankets());
        assertEquals(20, realJourney.getToppers());
        assertEquals(6, realJourney.getTouristCages());
        assertEquals(4, realJourney.getThickCages());
        assertEquals(1, realJourney.getTopperCages());
    }
}
