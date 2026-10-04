package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class ClosedSellerWeekTest {

    @Test
    void kaliningradWeekClosesAtLocalMidnightNotAtEndOfTradingDay() {
        ClosedSellerWeek target = new ClosedSellerWeek(LocalDate.of(2026, 9, 28),
                ZoneId.of("Europe/Kaliningrad"));
        assertThat(target.end()).isEqualTo(LocalDate.of(2026, 10, 4));
        assertThat(target.closesAt()).isEqualTo(Instant.parse("2026-10-04T22:00:00Z"));
        assertThat(target.isClosedAt(target.closesAt().minusNanos(1))).isFalse();
        assertThat(target.isClosedAt(target.closesAt())).isTrue();
        assertThat(ClosedSellerWeek.latest(target.closesAt().minusNanos(1), target.zone().getId()))
                .isEqualTo(target.previous());
        assertThat(ClosedSellerWeek.latest(target.closesAt(), target.zone().getId())).isEqualTo(target);
    }

    @Test
    void closureUsesCalendarDatesAcrossShortAndLongDstWeeks() {
        ZoneId zone = ZoneId.of("Europe/Berlin");
        ClosedSellerWeek spring = new ClosedSellerWeek(LocalDate.of(2026, 3, 23), zone);
        ClosedSellerWeek autumn = new ClosedSellerWeek(LocalDate.of(2026, 10, 19), zone);
        assertThat(Duration.between(spring.startInstant(), spring.closesAt())).isEqualTo(Duration.ofHours(167));
        assertThat(Duration.between(autumn.startInstant(), autumn.closesAt())).isEqualTo(Duration.ofHours(169));
    }

    @Test
    void baselineMustCoverBothWeeksNotOnlyTheReviewedWeek() {
        ClosedSellerWeek week = new ClosedSellerWeek(LocalDate.of(2026, 10, 12),
                ZoneId.of("Europe/Kaliningrad"));
        Instant previousStart = week.previous().startInstant();
        assertThat(week.hasAuthoritativeComparison(previousStart)).isTrue();
        assertThat(week.hasAuthoritativeComparison(previousStart.plusNanos(1))).isFalse();
        assertThat(week.hasAuthoritativeComparison(week.startInstant())).isFalse();
    }

    @Test
    void explicitWeekSurvivesTwoLaterWeekBoundaries() {
        ClosedSellerWeek week = new ClosedSellerWeek(LocalDate.of(2026, 9, 28), ZoneId.of("UTC"));
        assertThat(week.isClosedAt(Instant.parse("2026-10-19T00:00:00Z"))).isTrue();
        assertThat(week.start()).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(week.previous().end()).isEqualTo(LocalDate.of(2026, 9, 27));
    }

    @Test
    void rejectsNonMondayAndInvalidTimezone() {
        assertThatThrownBy(() -> new ClosedSellerWeek(LocalDate.of(2026, 10, 4), ZoneId.of("UTC")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClosedSellerWeek.latest(Instant.EPOCH, "Not/AZone"))
                .isInstanceOf(java.time.DateTimeException.class);
    }
}
