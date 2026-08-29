package com.storeanalytics.interpretation.review;

import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.ComparisonKind.NO_BASE;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.Direction.UP;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.Effect.NEGATIVE;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.Materiality.MATERIAL;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.Materiality.NOT_EVALUATED;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricState.READY;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.Sufficiency.INSUFFICIENT;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.Sufficiency.LIMITED;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.Sufficiency.SUFFICIENT;
import static com.storeanalytics.interpretation.review.WeeklyReviewPolicyV1.MetricSpec.storeMoney;
import static com.storeanalytics.interpretation.review.WeeklyReviewPolicyV1.Polarity.LOWER_IS_BETTER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.interpretation.review.WeeklyReviewPolicyV1.RevenuePeriod;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricComparison;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.PeriodContext;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.RevenueDecomposition;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WeeklyReviewPolicyV1Test {

    private final WeeklyReviewPolicyV1 policy = new WeeklyReviewPolicyV1();

    @Test
    void resolvesAdjacentCompletedMondayToSundayWeeks() {
        PeriodContext period = policy.period(
                Instant.parse("2026-08-26T12:00:00Z"),
                "Europe/Moscow"
        );

        assertThat(period.current().start()).hasToString("2026-08-17");
        assertThat(period.current().end()).hasToString("2026-08-23");
        assertThat(period.previous().start()).hasToString("2026-08-10");
        assertThat(period.previous().end()).hasToString("2026-08-16");
    }

    @Test
    void keepsNoBaseDistinctFromZeroChange() {
        MetricComparison comparison = policy.compare(
                storeMoney("RETURN_REVENUE", LOWER_IS_BETTER),
                new BigDecimal("15000.00"),
                BigDecimal.ZERO.setScale(2),
                READY,
                SUFFICIENT,
                null,
                null
        );

        assertThat(comparison.comparisonKind()).isEqualTo(NO_BASE);
        assertThat(comparison.changePercent()).isNull();
        assertThat(comparison.direction()).isEqualTo(UP);
        assertThat(comparison.effect()).isEqualTo(NEGATIVE);
        assertThat(comparison.materiality()).isEqualTo(NOT_EVALUATED);
    }

    @Test
    void evaluatesMaterialityOnlyForSufficientMetrics() {
        MetricComparison sufficient = policy.compare(
                storeMoney("NET_REVENUE", WeeklyReviewPolicyV1.Polarity.HIGHER_IS_BETTER),
                new BigDecimal("110.00"),
                new BigDecimal("100.00"),
                READY,
                SUFFICIENT,
                null,
                null
        );
        MetricComparison limited = policy.compare(
                storeMoney("NET_REVENUE", WeeklyReviewPolicyV1.Polarity.HIGHER_IS_BETTER),
                new BigDecimal("110.00"),
                new BigDecimal("100.00"),
                READY,
                LIMITED,
                null,
                null
        );

        assertThat(sufficient.materiality()).isEqualTo(MATERIAL);
        assertThat(limited.materiality()).isEqualTo(NOT_EVALUATED);
    }

    @Test
    void appliesApprovedPerMetricSufficiencyBoundaries() {
        assertThat(policy.salesSufficiency(2)).isEqualTo(INSUFFICIENT);
        assertThat(policy.salesSufficiency(3)).isEqualTo(LIMITED);
        assertThat(policy.salesSufficiency(6)).isEqualTo(SUFFICIENT);
        assertThat(policy.workloadSufficiency(1, new BigDecimal("10.00")))
                .isEqualTo(LIMITED);
        assertThat(policy.workloadSufficiency(2, new BigDecimal("12.00")))
                .isEqualTo(SUFFICIENT);
        assertThat(policy.attachSufficiency(new BigDecimal("2.000")))
                .isEqualTo(INSUFFICIENT);
        assertThat(policy.attachSufficiency(new BigDecimal("4.000")))
                .isEqualTo(LIMITED);
        assertThat(policy.attachSufficiency(new BigDecimal("5.000")))
                .isEqualTo(SUFFICIENT);
        assertThat(policy.teamBenchmarkAllowed(2)).isFalse();
        assertThat(policy.teamBenchmarkAllowed(3)).isTrue();
    }

    @Test
    void projectsSalesReturnsAndNetRevenueWithVerifiableIdentity() {
        RevenuePeriod current = revenue("100000.00", "15000.00", 8, 2);
        RevenuePeriod previous = revenue("90000.00", "5000.00", 7, 1);

        RevenueDecomposition result = policy.revenueDecomposition(current, previous);

        assertThat(result.identityValid()).isTrue();
        assertThat(result.salesRevenue().current()).isEqualByComparingTo("100000.00");
        assertThat(result.returnRevenue().current()).isEqualByComparingTo("15000.00");
        assertThat(result.netRevenue().current()).isEqualByComparingTo("85000.00");
        assertThat(policy.averageSale(current)).isEqualByComparingTo("12500.00");
    }

    @Test
    void rejectsRevenuePayloadWhenIdentityDoesNotHold() {
        RevenuePeriod invalid = new RevenuePeriod(
                new BigDecimal("100.00"),
                new BigDecimal("10.00"),
                new BigDecimal("95.00"),
                1,
                1
        );

        assertThatThrownBy(() -> policy.revenueDecomposition(
                invalid,
                revenue("100.00", "10.00", 1, 1)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("revenue identity");
    }

    @Test
    void usesVersionsAfterCurrentProductionSnapshotPolicy() {
        assertThat(WeeklyReviewPolicyV1.FACTS_SCHEMA_VERSION).isEqualTo(2);
        assertThat(WeeklyReviewPolicyV1.VERSIONS.metricsPolicy())
                .isEqualTo("weekly-metrics-v4");
        assertThat(WeeklyReviewPolicyV1.VERSIONS.snapshotPolicy())
                .isEqualTo("weekly-snapshot-v7");
        assertThat(WeeklyReviewPolicyV1.VERSIONS.qualityPolicy())
                .isEqualTo("weekly-quality-v4");
    }

    private RevenuePeriod revenue(
            String sales,
            String returns,
            long saleCount,
            long returnCount
    ) {
        BigDecimal salesAmount = new BigDecimal(sales);
        BigDecimal returnAmount = new BigDecimal(returns);
        return new RevenuePeriod(
                salesAmount,
                returnAmount,
                salesAmount.subtract(returnAmount),
                saleCount,
                returnCount
        );
    }
}
