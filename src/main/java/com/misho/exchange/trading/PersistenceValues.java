package com.misho.exchange.trading;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

final class PersistenceValues {

    private PersistenceValues() { }

    static BigDecimal price(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException("Price must be positive.");
        }
        BigDecimal scaled;
        try {
            scaled = value.setScale(4, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Price must have at most four decimal places.");
        }
        if (scaled.precision() > 19) {
            throw new IllegalArgumentException("Price exceeds NUMERIC(19,4).");
        }
        return scaled;
    }

    static Instant timestamp(Instant value) {
        if (value == null) {
            throw new IllegalArgumentException("Timestamp is required.");
        }
        return value.truncatedTo(ChronoUnit.MICROS);
    }
}
