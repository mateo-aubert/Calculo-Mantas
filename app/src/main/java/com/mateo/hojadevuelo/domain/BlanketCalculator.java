package com.mateo.hojadevuelo.domain;

import com.mateo.hojadevuelo.data.AircraftCatalog;

import java.util.List;

public final class BlanketCalculator {
    private static final int CABIN_AND_HOLD_LOADS = 2;

    private static final int TOURIST_CAGE_CAPACITY = 16;
    private static final int THICK_CAGE_CAPACITY = 8;
    private static final int TOPPER_CAGE_CAPACITY = 20;

    private BlanketCalculator() {
    }

    public static Summary calculate(AircraftCatalog catalog, List<String> registrations) {
        return calculateLoads(catalog, registrations, CABIN_AND_HOLD_LOADS);
    }

    public static Summary calculateSingleLoad(AircraftCatalog catalog, List<String> registrations) {
        return calculateLoads(catalog, registrations, 1);
    }

    private static Summary calculateLoads(
            AircraftCatalog catalog,
            List<String> registrations,
            int loadsPerRegistration) {
        int touristBlankets = 0;
        int thickBlankets = 0;
        int toppers = 0;

        for (String registration : registrations) {
            Requirement requirement = requirementFor(catalog, registration);
            touristBlankets += requirement.touristBlankets * loadsPerRegistration;
            thickBlankets += requirement.thickBlankets * loadsPerRegistration;
            toppers += requirement.toppers * loadsPerRegistration;
        }

        return new Summary(
                registrations.size(),
                touristBlankets,
                thickBlankets,
                toppers,
                cagesFor(touristBlankets, TOURIST_CAGE_CAPACITY),
                cagesFor(thickBlankets, THICK_CAGE_CAPACITY),
                cagesFor(toppers, TOPPER_CAGE_CAPACITY));
    }

    public static Summary combine(Summary first, Summary second) {
        int touristBlankets = first.touristBlankets + second.touristBlankets;
        int thickBlankets = first.thickBlankets + second.thickBlankets;
        int toppers = first.toppers + second.toppers;
        return new Summary(
                first.aircraftCount + second.aircraftCount,
                touristBlankets,
                thickBlankets,
                toppers,
                cagesFor(touristBlankets, TOURIST_CAGE_CAPACITY),
                cagesFor(thickBlankets, THICK_CAGE_CAPACITY),
                cagesFor(toppers, TOPPER_CAGE_CAPACITY));
    }

    private static Requirement requirementFor(AircraftCatalog catalog, String registration) {
        String normalized = AircraftCatalog.normalizeRegistration(registration);
        String category = catalog.categoryFor(normalized);
        if (AircraftCatalog.CATEGORY_PREMIUM.equals(category)) {
            return new Requirement(30, 10, 8);
        }
        if (AircraftCatalog.CATEGORY_800.equals(category)) {
            return new Requirement(29, 9, 6);
        }
        if (AircraftCatalog.CATEGORY_900.equals(category)) {
            return new Requirement(32, 11, 8);
        }
        return new Requirement(0, 0, 0);
    }

    private static int cagesFor(int total, int capacity) {
        if (total <= 0) {
            return 0;
        }
        return (total + capacity - 1) / capacity;
    }

    private static final class Requirement {
        private final int touristBlankets;
        private final int thickBlankets;
        private final int toppers;

        private Requirement(int touristBlankets, int thickBlankets, int toppers) {
            this.touristBlankets = touristBlankets;
            this.thickBlankets = thickBlankets;
            this.toppers = toppers;
        }
    }

    public static final class Summary {
        private final int aircraftCount;
        private final int touristBlankets;
        private final int thickBlankets;
        private final int toppers;
        private final int touristCages;
        private final int thickCages;
        private final int topperCages;

        private Summary(
                int aircraftCount,
                int touristBlankets,
                int thickBlankets,
                int toppers,
                int touristCages,
                int thickCages,
                int topperCages) {
            this.aircraftCount = aircraftCount;
            this.touristBlankets = touristBlankets;
            this.thickBlankets = thickBlankets;
            this.toppers = toppers;
            this.touristCages = touristCages;
            this.thickCages = thickCages;
            this.topperCages = topperCages;
        }

        public int getAircraftCount() {
            return aircraftCount;
        }

        public int getTouristBlankets() {
            return touristBlankets;
        }

        public int getThickBlankets() {
            return thickBlankets;
        }

        public int getToppers() {
            return toppers;
        }

        public int getTouristCages() {
            return touristCages;
        }

        public int getThickCages() {
            return thickCages;
        }

        public int getTopperCages() {
            return topperCages;
        }
    }
}
