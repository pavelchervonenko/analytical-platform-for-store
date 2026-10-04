package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/** Calendar week identity; closure is based on the store zone, never on elapsed hours. */
public record ClosedSellerWeek(LocalDate start, ZoneId zone) {

    public ClosedSellerWeek {
        requireNonNull(start, "start");
        requireNonNull(zone, "zone");
        require(start.getDayOfWeek() == DayOfWeek.MONDAY, "Seller week must start on Monday");
    }

    public static ClosedSellerWeek latest(Instant now, String timezone) {
        ZoneId zone = ZoneId.of(requireNonNull(timezone, "timezone"));
        LocalDate monday = LocalDate.ofInstant(requireNonNull(now, "now"), zone)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return new ClosedSellerWeek(monday.minusWeeks(1), zone);
    }

    public LocalDate end() {
        return start.plusDays(6);
    }

    public Instant startInstant() {
        return start.atStartOfDay(zone).toInstant();
    }

    public Instant closesAt() {
        return start.plusWeeks(1).atStartOfDay(zone).toInstant();
    }

    public boolean isClosedAt(Instant now) {
        return !requireNonNull(now, "now").isBefore(closesAt());
    }

    public ClosedSellerWeek previous() {
        return new ClosedSellerWeek(start.minusWeeks(1), zone);
    }

    /** Both comparison weeks must lie inside the proven membership history. */
    public boolean hasAuthoritativeComparison(Instant authoritativeFrom) {
        return !previous().startInstant().isBefore(requireNonNull(authoritativeFrom, "authoritativeFrom"));
    }
}
