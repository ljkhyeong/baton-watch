package com.personal.baton.watch.domain.monitoring;

import java.util.Objects;
import java.util.regex.Pattern;

public record ResourceReference(String value) {

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    public ResourceReference {
        Objects.requireNonNull(value, "value");
        if (!ALLOWED.matcher(value).matches()) {
            throw new IllegalArgumentException("resource reference must contain 1-128 allowed characters");
        }
    }

    @Override
    public String toString() {
        return "[resource-reference]";
    }
}
