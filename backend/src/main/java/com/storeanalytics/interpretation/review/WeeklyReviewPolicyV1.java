package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.ComparisonKind.NO_BASE;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.ComparisonKind.NON_POSITIVE_BASE;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.ComparisonKind.PERCENT_AVAILABLE;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.ComparisonKind.UNAVAILABLE;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.Direction.DOWN;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.Direction.FLAT;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.Direction.UNKNOWN;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.Direction.UP;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.Materiality.MATERIAL;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.Materiality.NOT_EVALUATED;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.Materiality.NOT_MATERIAL;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.Sufficiency.INSUFFICIENT;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.Sufficiency.LIMITED;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.Sufficiency.SUFFICIENT;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse.ComparisonKind;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.DateRange;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Direction;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Effect;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Materiality;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricComparison;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.PeriodContext;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.RevenueDecomposition;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Sample;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Sufficiency;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Unit;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.VersionSet;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;

/** Versioned deterministic thresholds and formulas for weekly-review-contract-v2. */
public final class WeeklyReviewPolicyV1 {

    public static final int FACTS_SCHEMA_VERSION = 2;
    public static final VersionSet VERSIONS = new VersionSet(
            "weekly-metrics-v4",
            "weekly-snapshot-v7",
            "weekly-quality-v4"
    );

    private static final int PERCENT_SCALE = 2;
    private static final BigDecimal STORE_RELATIVE_THRESHOLD = new BigDecimal("5.00");
    private static final BigDecimal EMPLOYEE_RELATIVE_THRESHOLD = new BigDecimal("10.00");
    private static final BigDecimal SHARE_THRESHOLD = new BigDecimal("3.00");
    private static final BigDecimal ATTACH_THRESHOLD = new BigDecimal("5.00");
    private static final BigDecimal MINIMUM_WORKED_HOURS = new BigDecimal("12.00");
    private static final DateTimeFormatter PERIOD_LABEL = DateTimeFormatter.ofLocalizedDate(
            FormatStyle.MEDIUM
    ).withLocale(Locale.forLanguageTag("ru-RU"));

    public PeriodContext period(Instant now, String timezone) {
        ZoneId zone = ZoneId.of(timezone);
        LocalDate currentWeekStart = LocalDate.ofInstant(now, zone)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        DateRange current = new DateRange(
                currentWeekStart.minusWeeks(1),
                currentWeekStart.minusDays(1)
        );
        DateRange previous = new DateRange(
                current.start().minusWeeks(1),
                current.end().minusWeeks(1)
        );
        return new PeriodContext(
                timezone,
                current,
                previous,
                label(current),
                label(previous)
        );
    }

    public Sufficiency salesSufficiency(long completedSales) {
        require(completedSales >= 0, "completedSales must not be negative");
        if (completedSales < 3) {
            return INSUFFICIENT;
        }
        return completedSales < 6 ? LIMITED : SUFFICIENT;
    }

    public Sufficiency workloadSufficiency(long shiftCount, BigDecimal workedHours) {
        require(shiftCount >= 0, "shiftCount must not be negative");
        if (shiftCount == 0 || workedHours == null || workedHours.signum() <= 0) {
            return INSUFFICIENT;
        }
        if (shiftCount == 1 || workedHours.compareTo(MINIMUM_WORKED_HOURS) < 0) {
            return LIMITED;
        }
        return SUFFICIENT;
    }

    public Sufficiency attachSufficiency(BigDecimal denominator) {
        if (denominator == null || denominator.compareTo(BigDecimal.valueOf(3)) < 0) {
            return INSUFFICIENT;
        }
        return denominator.compareTo(BigDecimal.valueOf(5)) < 0
                ? LIMITED
                : SUFFICIENT;
    }

    public boolean teamBenchmarkAllowed(long eligibleCount) {
        return eligibleCount >= 3;
    }

    public MetricComparison compare(
            MetricSpec spec,
            BigDecimal current,
            BigDecimal previous,
            MetricState metricState,
            Sufficiency sufficiency,
            Sample currentSample,
            Sample previousSample
    ) {
        requireNonNull(spec, "spec");
        requireNonNull(metricState, "metricState");
        requireNonNull(sufficiency, "sufficiency");
        BigDecimal delta = current == null || previous == null
                ? null
                : current.subtract(previous);
        ComparisonKind comparisonKind = comparisonKind(current, previous);
        BigDecimal changePercent = comparisonKind == PERCENT_AVAILABLE
                ? delta.multiply(BigDecimal.valueOf(100)).divide(
                        previous.abs(), PERCENT_SCALE, RoundingMode.HALF_UP
                )
                : null;
        Direction direction = direction(delta);
        Effect effect = effect(direction, spec.polarity());
        Materiality materiality = materiality(
                spec, delta, changePercent, sufficiency, metricState
        );
        return new MetricComparison(
                spec.metricId(),
                spec.code(),
                spec.label(),
                spec.unit(),
                current,
                previous,
                delta,
                changePercent,
                comparisonKind,
                direction,
                effect,
                metricState,
                sufficiency,
                materiality,
                currentSample,
                previousSample,
                List.of(spec.evidenceRef())
        );
    }

    public RevenueDecomposition revenueDecomposition(
            RevenuePeriod current,
            RevenuePeriod previous
    ) {
        requireNonNull(current, "current");
        requireNonNull(previous, "previous");
        require(current.identityValid(), "current revenue identity must be valid");
        require(previous.identityValid(), "previous revenue identity must be valid");
        return new RevenueDecomposition(
                compare(
                        MetricSpec.storeMoney("SALES_REVENUE", Polarity.HIGHER_IS_BETTER),
                        current.salesRevenue(), previous.salesRevenue(),
                        MetricState.READY, SUFFICIENT, null, null
                ),
                compare(
                        MetricSpec.storeMoney("RETURN_REVENUE", Polarity.LOWER_IS_BETTER),
                        current.returnRevenue(), previous.returnRevenue(),
                        MetricState.READY, SUFFICIENT, null, null
                ),
                compare(
                        MetricSpec.storeMoney("NET_REVENUE", Polarity.HIGHER_IS_BETTER),
                        current.netRevenue(), previous.netRevenue(),
                        MetricState.READY, SUFFICIENT, null, null
                ),
                compare(
                        MetricSpec.storeCount("SALE_DOCUMENT_COUNT"),
                        BigDecimal.valueOf(current.saleDocumentCount()),
                        BigDecimal.valueOf(previous.saleDocumentCount()),
                        MetricState.READY, SUFFICIENT, null, null
                ),
                compare(
                        MetricSpec.storeCount("RETURN_DOCUMENT_COUNT"),
                        BigDecimal.valueOf(current.returnDocumentCount()),
                        BigDecimal.valueOf(previous.returnDocumentCount()),
                        MetricState.READY, SUFFICIENT, null, null
                ),
                true
        );
    }

    public BigDecimal averageSale(RevenuePeriod period) {
        requireNonNull(period, "period");
        if (period.saleDocumentCount() == 0) {
            return null;
        }
        return period.salesRevenue().divide(
                BigDecimal.valueOf(period.saleDocumentCount()),
                2,
                RoundingMode.HALF_UP
        );
    }

    public BigDecimal storeRelativeThreshold() {
        return STORE_RELATIVE_THRESHOLD;
    }

    public BigDecimal employeeRelativeThreshold() {
        return EMPLOYEE_RELATIVE_THRESHOLD;
    }

    public BigDecimal shareThreshold() {
        return SHARE_THRESHOLD;
    }

    public BigDecimal attachThreshold() {
        return ATTACH_THRESHOLD;
    }

    private String label(DateRange range) {
        return PERIOD_LABEL.format(range.start()) + " — " + PERIOD_LABEL.format(range.end());
    }

    private ComparisonKind comparisonKind(BigDecimal current, BigDecimal previous) {
        if (current == null || previous == null) {
            return UNAVAILABLE;
        }
        if (previous.signum() == 0) {
            return NO_BASE;
        }
        return previous.signum() < 0 ? NON_POSITIVE_BASE : PERCENT_AVAILABLE;
    }

    private Direction direction(BigDecimal delta) {
        if (delta == null) {
            return UNKNOWN;
        }
        return switch (delta.signum()) {
            case -1 -> DOWN;
            case 0 -> FLAT;
            default -> UP;
        };
    }

    private Effect effect(Direction direction, Polarity polarity) {
        if (direction == UNKNOWN) {
            return Effect.UNKNOWN;
        }
        if (direction == FLAT || polarity == Polarity.CONTEXT) {
            return Effect.NEUTRAL;
        }
        boolean positive = direction == UP && polarity == Polarity.HIGHER_IS_BETTER
                || direction == DOWN && polarity == Polarity.LOWER_IS_BETTER;
        return positive ? Effect.POSITIVE : Effect.NEGATIVE;
    }

    private Materiality materiality(
            MetricSpec spec,
            BigDecimal delta,
            BigDecimal changePercent,
            Sufficiency sufficiency,
            MetricState metricState
    ) {
        if (metricState != MetricState.READY || sufficiency != SUFFICIENT) {
            return NOT_EVALUATED;
        }
        BigDecimal measured = spec.deltaMode() == DeltaMode.RELATIVE
                ? changePercent
                : delta;
        if (measured == null || spec.materialityThreshold() == null) {
            return NOT_EVALUATED;
        }
        return measured.abs().compareTo(spec.materialityThreshold()) >= 0
                ? MATERIAL
                : NOT_MATERIAL;
    }

    public enum Polarity {
        HIGHER_IS_BETTER,
        LOWER_IS_BETTER,
        CONTEXT
    }

    public enum DeltaMode {
        RELATIVE,
        ABSOLUTE
    }

    public record MetricSpec(
            String metricId,
            String code,
            String label,
            Unit unit,
            Polarity polarity,
            DeltaMode deltaMode,
            BigDecimal materialityThreshold,
            String evidenceRef
    ) {

        public MetricSpec {
            requireNonNull(polarity, "polarity");
            requireNonNull(deltaMode, "deltaMode");
        }

        public static MetricSpec storeMoney(String code, Polarity polarity) {
            String normalized = requireNonNull(code, "code");
            return new MetricSpec(
                    "store:" + normalized.toLowerCase(Locale.ROOT),
                    normalized,
                    label(normalized),
                    Unit.RUB,
                    polarity,
                    DeltaMode.RELATIVE,
                    STORE_RELATIVE_THRESHOLD,
                    "STORE." + normalized
            );
        }

        public static MetricSpec storeCount(String code) {
            String normalized = requireNonNull(code, "code");
            return new MetricSpec(
                    "store:" + normalized.toLowerCase(Locale.ROOT),
                    normalized,
                    label(normalized),
                    Unit.COUNT,
                    Polarity.CONTEXT,
                    DeltaMode.RELATIVE,
                    STORE_RELATIVE_THRESHOLD,
                    "STORE." + normalized
            );
        }

        private static String label(String code) {
            return switch (code) {
                case "SALES_REVENUE" -> "Продажи";
                case "RETURN_REVENUE" -> "Возвраты";
                case "NET_REVENUE" -> "Чистая выручка";
                case "SALE_DOCUMENT_COUNT" -> "Количество продаж";
                case "RETURN_DOCUMENT_COUNT" -> "Количество возвратов";
                default -> code;
            };
        }
    }

    public record RevenuePeriod(
            BigDecimal salesRevenue,
            BigDecimal returnRevenue,
            BigDecimal netRevenue,
            long saleDocumentCount,
            long returnDocumentCount
    ) {

        public RevenuePeriod {
            requireNonNull(salesRevenue, "salesRevenue");
            requireNonNull(returnRevenue, "returnRevenue");
            requireNonNull(netRevenue, "netRevenue");
            require(salesRevenue.signum() >= 0, "salesRevenue must not be negative");
            require(returnRevenue.signum() >= 0, "returnRevenue must not be negative");
            require(saleDocumentCount >= 0, "saleDocumentCount must not be negative");
            require(returnDocumentCount >= 0, "returnDocumentCount must not be negative");
        }

        public boolean identityValid() {
            return salesRevenue.subtract(returnRevenue).compareTo(netRevenue) == 0;
        }
    }
}
