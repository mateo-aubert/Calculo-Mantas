package com.mateo.hojadevuelo.model;

import java.util.Objects;

public final class Aircraft {
    private final String registration;
    private final String model;

    public Aircraft(String registration, String model) {
        this.registration = Objects.requireNonNull(registration);
        this.model = Objects.requireNonNull(model);
    }

    public String getRegistration() {
        return registration;
    }

    public String getModel() {
        return model;
    }
}
