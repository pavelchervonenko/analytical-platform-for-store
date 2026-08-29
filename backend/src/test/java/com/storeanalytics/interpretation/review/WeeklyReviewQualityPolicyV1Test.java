package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.interpretation.review.WeeklyReviewQualityPolicyV1.Decision;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.DateRange;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.ReportState;
import com.storeanalytics.metrics.service.StoreKpiDataQuality;
import com.storeanalytics.metrics.service.StoreKpiResult;
import com.storeanalytics.store.service.StoreDataFreshnessStatus;
import com.storeanalytics.store.service.StoreDataStatusView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WeeklyReviewQualityPolicyV1Test {

    private static final DateRange CURRENT = new DateRange(
            LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 23)
    );
    private static final DateRange PREVIOUS = new DateRange(
            LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 16)
    );

    private final WeeklyReviewQualityPolicyV1 policy = new WeeklyReviewQualityPolicyV1();

    @Test
    void ignoresLatestSyncFailureWhenSalesAndReturnsCoverTheClosedWeek() {
        Decision result = policy.decide(
                source(StoreDataFreshnessStatus.ERROR, CURRENT.end(), CURRENT.end(), null),
                kpi(quality(0, 0, 0, 99)),
                kpi(quality(0, 0, 0, 99)),
                CURRENT,
                PREVIOUS
        );

        assertThat(result.reportState()).isEqualTo(ReportState.READY);
        assertThat(result.limitations()).isEmpty();
    }

    @Test
    void doesNotTurnAnUnrelatedGlobalIssueIntoClassificationWarning() {
        Decision result = policy.decide(
                source(StoreDataFreshnessStatus.CURRENT, CURRENT.end(), CURRENT.end(), null),
                kpi(quality(0, 0, 0, 5)),
                kpi(quality(0, 0, 0, 5)),
                CURRENT,
                PREVIOUS
        );

        assertThat(result.limitations()).noneMatch(limitation ->
                limitation.code().equals("PRODUCTS_UNCLASSIFIED")
        );
    }

    @Test
    void routesClassificationOnlyToStructureAndAttach() {
        Decision result = policy.decide(
                source(StoreDataFreshnessStatus.CURRENT, CURRENT.end(), CURRENT.end(), null),
                kpi(quality(1, 0, 0, 1)),
                kpi(quality(0, 0, 0, 1)),
                CURRENT,
                PREVIOUS
        );

        assertThat(result.reportState()).isEqualTo(ReportState.PARTIAL);
        assertThat(result.limitations()).singleElement().satisfies(limitation -> {
            assertThat(limitation.code()).isEqualTo("PRODUCTS_UNCLASSIFIED");
            assertThat(limitation.affectedBlockIds()).containsExactly("sales-structure");
            assertThat(limitation.affectedMetricCodes())
                    .containsExactly("SALES_STRUCTURE", "ATTACH");
        });
    }

    @Test
    void namesConsistencyProblemsWithoutCallingThemClassification() {
        Decision result = policy.decide(
                source(StoreDataFreshnessStatus.CURRENT, CURRENT.end(), CURRENT.end(), null),
                kpi(quality(0, 0, 2, 2)),
                kpi(quality(0, 0, 0, 2)),
                CURRENT,
                PREVIOUS
        );

        assertThat(result.limitations()).singleElement().satisfies(limitation -> {
            assertThat(limitation.code())
                    .isEqualTo("SALES_OR_RETURNS_CONSISTENCY_ISSUE");
            assertThat(limitation.summary()).contains("продаж или возвратов");
        });
    }

    @Test
    void unattributedReturnsLimitOnlyPeopleBlocksAndStayNonBlocking() {
        Decision result = policy.decide(
                source(StoreDataFreshnessStatus.CURRENT, CURRENT.end(), CURRENT.end(), null),
                kpi(quality(0, 0, 0, 0)),
                kpi(quality(0, 0, 0, 0)),
                CURRENT,
                PREVIOUS,
                2,
                0
        );

        assertThat(result.reportState()).isEqualTo(ReportState.PARTIAL);
        assertThat(result.sourceCoverage()).anySatisfy(coverage -> {
            assertThat(coverage.sourceCode().name()).isEqualTo("EMPLOYEE_ATTRIBUTION");
            assertThat(coverage.requiredForReport()).isFalse();
            assertThat(coverage.affectedBlockIds()).containsExactly("team", "employees");
        });
        assertThat(result.limitations()).singleElement().satisfies(limitation -> {
            assertThat(limitation.code()).isEqualTo("RETURN_EMPLOYEE_UNATTRIBUTED");
            assertThat(limitation.affectedCount()).isEqualTo(2);
            assertThat(limitation.evidenceRefs())
                    .containsExactly("EMPLOYEE_ATTRIBUTION.CURRENT");
        });
    }

    @Test
    void blocksOnlyWhenRequiredSalesOrReturnsDoNotCoverCurrentWeek() {
        Decision result = policy.decide(
                source(
                        StoreDataFreshnessStatus.STALE,
                        CURRENT.end(),
                        PREVIOUS.end(),
                        PREVIOUS.end()
                ),
                kpi(quality(0, 0, 0, 0)),
                kpi(quality(0, 0, 0, 0)),
                CURRENT,
                PREVIOUS
        );

        assertThat(result.reportState()).isEqualTo(ReportState.BLOCKED);
        assertThat(result.limitations()).singleElement().satisfies(limitation ->
                assertThat(limitation.code()).isEqualTo("RETURNS_COVERAGE_INCOMPLETE")
        );
    }

    private StoreDataStatusView source(
            StoreDataFreshnessStatus status,
            LocalDate salesThrough,
            LocalDate returnsThrough,
            LocalDate combinedThrough
    ) {
        return new StoreDataStatusView(
                UUID.randomUUID(),
                status,
                CURRENT.end(),
                combinedThrough,
                salesThrough,
                returnsThrough,
                null,
                Instant.parse("2026-08-24T05:00:00Z"),
                null,
                100,
                "latest job failed",
                Instant.parse("2026-08-24T05:00:00Z"),
                Instant.parse("2026-08-26T12:00:00Z")
        );
    }

    private StoreKpiResult kpi(StoreKpiDataQuality quality) {
        return new StoreKpiResult(
                UUID.randomUUID(),
                CURRENT.start(),
                CURRENT.end(),
                "store-kpi-v1",
                new BigDecimal("100.00"),
                BigDecimal.ONE.setScale(3),
                new BigDecimal("50.00"),
                new BigDecimal("50.00"),
                new BigDecimal("50.00"),
                quality
        );
    }

    private StoreKpiDataQuality quality(
            long unmapped,
            long missingCost,
            long periodConsistency,
            long globalIssues
    ) {
        return new StoreKpiDataQuality(
                missingCost == 0,
                1,
                unmapped,
                missingCost,
                0,
                periodConsistency,
                globalIssues
        );
    }
}
