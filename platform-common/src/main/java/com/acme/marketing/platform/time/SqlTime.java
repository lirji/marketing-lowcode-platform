package com.acme.marketing.platform.time;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Objects;

/**
 * Canonical UTC representation used by the R1 schemas that persist instants as text.
 * A fixed fractional width makes binary/lexical database comparisons identical to
 * {@link Instant}'s chronological ordering.
 */
public final class SqlTime {
    private static final DateTimeFormatter FORMATTER = new DateTimeFormatterBuilder()
            .appendInstant(9)
            .toFormatter();

    private SqlTime() { }

    public static String format(Instant value) {
        return FORMATTER.format(Objects.requireNonNull(value, "value"));
    }
}
