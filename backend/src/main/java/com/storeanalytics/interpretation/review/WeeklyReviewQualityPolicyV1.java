package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse.CoverageState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.DateRange;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Limitation;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.QualitySummary;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.ReportState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.SourceCode;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.SourceCoverage;
import com.storeanalytics.metrics.service.StoreKpiResult;
import com.storeanalytics.store.service.StoreDataStatusView;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Routes data quality to the exact weekly-review metrics it can affect. */
public final class WeeklyReviewQualityPolicyV1 {

    public Decision decide(
            StoreDataStatusView source,
            StoreKpiResult current,
            StoreKpiResult previous,
            DateRange currentPeriod,
            DateRange previousPeriod
    ) {
        return decide(
                source,
                current,
                previous,
                currentPeriod,
                previousPeriod,
                0,
                0
        );
    }

    public Decision decide(
            StoreDataStatusView source,
            StoreKpiResult current,
            StoreKpiResult previous,
            DateRange currentPeriod,
            DateRange previousPeriod,
            long currentUnattributedReturns,
            long previousUnattributedReturns
    ) {
        StoreDataStatusView status = requireNonNull(source, "source");
        StoreKpiResult currentKpi = requireNonNull(current, "current");
        StoreKpiResult previousKpi = requireNonNull(previous, "previous");
        DateRange currentRange = requireNonNull(currentPeriod, "currentPeriod");
        DateRange previousRange = requireNonNull(previousPeriod, "previousPeriod");
        List<SourceCoverage> coverage = List.of(
                coverage(
                        SourceCode.SALES,
                        status.salesDataThroughDate(),
                        currentRange,
                        previousRange
                ),
                coverage(
                        SourceCode.RETURNS,
                        status.returnsDataThroughDate(),
                        currentRange,
                        previousRange
                ),
                employeeAttributionCoverage(
                        currentRange,
                        previousRange,
                        currentUnattributedReturns,
                        previousUnattributedReturns
                )
        );
        List<Limitation> limitations = new ArrayList<>();
        coverage.stream()
                .filter(SourceCoverage::requiredForReport)
                .filter(item -> item.state() != CoverageState.COMPLETE)
                .map(item -> sourceLimitation(item, currentRange, previousRange))
                .forEach(limitations::add);
        addClassificationLimitations(
                currentKpi, previousKpi, currentRange, previousRange, limitations
        );
        addCostLimitations(currentKpi, previousKpi, currentRange, previousRange, limitations);
        addConsistencyLimitations(
                currentKpi, previousKpi, currentRange, previousRange, limitations
        );
        addEmployeeAttributionLimitations(
                currentRange,
                previousRange,
                currentUnattributedReturns,
                previousUnattributedReturns,
                limitations
        );

        boolean blocked = coverage.stream()
                .filter(SourceCoverage::requiredForReport)
                .anyMatch(item ->
                item.state() == CoverageState.MISSING
                        || item.state() == CoverageState.PARTIAL
                        && item.currentThroughDate() != null
                        && item.currentThroughDate().isBefore(currentRange.end())
        );
        ReportState reportState = blocked
                ? ReportState.BLOCKED
                : limitations.isEmpty() ? ReportState.READY : ReportState.PARTIAL;
        int blockingCount = Math.toIntExact(limitations.stream()
                .filter(item -> "BLOCKING".equals(item.severity()))
                .count());
        int warningCount = limitations.size() - blockingCount;
        int affectedBlocks = Math.toIntExact(limitations.stream()
                .flatMap(item -> item.affectedBlockIds().stream())
                .distinct()
                .count());
        return new Decision(
                reportState,
                coverage,
                List.copyOf(limitations),
                new QualitySummary(
                        blockingCount,
                        warningCount,
                        affectedBlocks,
                        message(reportState, warningCount)
                )
        );
    }

    private SourceCoverage employeeAttributionCoverage(
            DateRange current,
            DateRange previous,
            long currentCount,
            long previousCount
    ) {
        boolean complete = currentCount == 0 && previousCount == 0;
        return new SourceCoverage(
                SourceCode.EMPLOYEE_ATTRIBUTION,
                false,
                List.of("team", "employees"),
                current.end(),
                previous.end(),
                complete ? CoverageState.COMPLETE : CoverageState.PARTIAL,
                complete ? null : "Часть возвратов не распределена между сотрудниками"
        );
    }

    private void addEmployeeAttributionLimitations(
            DateRange current,
            DateRange previous,
            long currentCount,
            long previousCount,
            List<Limitation> limitations
    ) {
        addEmployeeAttributionLimitation(
                "current", current, currentCount, limitations
        );
        addEmployeeAttributionLimitation(
                "previous", previous, previousCount, limitations
        );
    }

    private void addEmployeeAttributionLimitation(
            String periodCode,
            DateRange period,
            long count,
            List<Limitation> limitations
    ) {
        if (count <= 0) {
            return;
        }
        limitations.add(new Limitation(
                "employee-attribution:" + periodCode,
                "RETURN_EMPLOYEE_UNATTRIBUTED",
                "WARNING",
                "TEAM",
                null,
                List.of("team", "employees"),
                List.of("EMPLOYEE_NET_REVENUE", "EMPLOYEE_ADDITIONAL_REVENUE"),
                period,
                Math.toIntExact(count),
                "Возвраты вошли в итог магазина, но не распределены между сотрудниками",
                "Связать возвраты с исходными продажами и их продавцами",
                List.of("EMPLOYEE_ATTRIBUTION." + periodCode.toUpperCase())
        ));
    }

    private SourceCoverage coverage(
            SourceCode sourceCode,
            LocalDate through,
            DateRange current,
            DateRange previous
    ) {
        CoverageState state;
        if (through == null || through.isBefore(previous.end())) {
            state = CoverageState.MISSING;
        } else if (through.isBefore(current.end())) {
            state = CoverageState.PARTIAL;
        } else {
            state = CoverageState.COMPLETE;
        }
        return new SourceCoverage(
                sourceCode,
                true,
                List.of("results"),
                through,
                through,
                state,
                state == CoverageState.COMPLETE
                        ? null
                        : "Источник не покрывает обе сравниваемые недели"
        );
    }

    private Limitation sourceLimitation(
            SourceCoverage coverage,
            DateRange current,
            DateRange previous
    ) {
        boolean currentMissing = coverage.currentThroughDate() == null
                || coverage.currentThroughDate().isBefore(current.end());
        return limitation(
                new Issue(
                        "source:" + coverage.sourceCode().name().toLowerCase(),
                        coverage.sourceCode() + "_COVERAGE_INCOMPLETE"
                ),
                currentMissing ? "BLOCKING" : "WARNING",
                List.of("results"),
                List.of("NET_REVENUE"),
                currentMissing ? current : previous,
                1,
                currentMissing
                        ? "Источник не покрывает завершённую неделю"
                        : "Источник не покрывает неделю сравнения"
        );
    }

    private void addClassificationLimitations(
            StoreKpiResult current,
            StoreKpiResult previous,
            DateRange currentPeriod,
            DateRange previousPeriod,
            List<Limitation> limitations
    ) {
        addCountLimitation(
                new Issue("classification:current", "PRODUCTS_UNCLASSIFIED"),
                List.of("sales-structure"),
                List.of("SALES_STRUCTURE", "ATTACH"),
                currentPeriod,
                current.dataQuality().unmappedItemCount(),
                "Часть товарных позиций недели не классифицирована",
                limitations
        );
        addCountLimitation(
                new Issue("classification:previous", "PRODUCTS_UNCLASSIFIED"),
                List.of("sales-structure"),
                List.of("SALES_STRUCTURE", "ATTACH"),
                previousPeriod,
                previous.dataQuality().unmappedItemCount(),
                "Часть товарных позиций недели сравнения не классифицирована",
                limitations
        );
    }

    private void addCostLimitations(
            StoreKpiResult current,
            StoreKpiResult previous,
            DateRange currentPeriod,
            DateRange previousPeriod,
            List<Limitation> limitations
    ) {
        addCountLimitation(
                new Issue("cost:missing:current", "COST_DATA_MISSING"),
                List.of("results"),
                List.of("GROSS_PROFIT", "MARGIN_PERCENT"),
                currentPeriod,
                current.dataQuality().missingCostItemCount(),
                "Для части позиций недели отсутствует себестоимость",
                limitations
        );
        addCountLimitation(
                new Issue("cost:missing:previous", "COST_DATA_MISSING"),
                List.of("results"),
                List.of("GROSS_PROFIT", "MARGIN_PERCENT"),
                previousPeriod,
                previous.dataQuality().missingCostItemCount(),
                "Для части позиций недели сравнения отсутствует себестоимость",
                limitations
        );
        addCountLimitation(
                new Issue("cost:zero:current", "UNEXPECTED_ZERO_COST"),
                List.of("results"),
                List.of("GROSS_PROFIT", "MARGIN_PERCENT"),
                currentPeriod,
                current.dataQuality().unexpectedZeroCostItemCount(),
                "Себестоимость части товаров недели требует проверки",
                limitations
        );
        addCountLimitation(
                new Issue("cost:zero:previous", "UNEXPECTED_ZERO_COST"),
                List.of("results"),
                List.of("GROSS_PROFIT", "MARGIN_PERCENT"),
                previousPeriod,
                previous.dataQuality().unexpectedZeroCostItemCount(),
                "Себестоимость части товаров недели сравнения требует проверки",
                limitations
        );
    }

    private void addConsistencyLimitations(
            StoreKpiResult current,
            StoreKpiResult previous,
            DateRange currentPeriod,
            DateRange previousPeriod,
            List<Limitation> limitations
    ) {
        addCountLimitation(
                new Issue("consistency:current", "SALES_OR_RETURNS_CONSISTENCY_ISSUE"),
                List.of("results"),
                List.of("NET_REVENUE"),
                currentPeriod,
                current.dataQuality().periodOpenConsistencyIssueCount(),
                "Есть проблемы согласованности продаж или возвратов недели",
                limitations
        );
        addCountLimitation(
                new Issue("consistency:previous", "SALES_OR_RETURNS_CONSISTENCY_ISSUE"),
                List.of("results"),
                List.of("NET_REVENUE"),
                previousPeriod,
                previous.dataQuality().periodOpenConsistencyIssueCount(),
                "Есть проблемы согласованности продаж или возвратов недели сравнения",
                limitations
        );
    }

    private void addCountLimitation(
            Issue issue,
            List<String> blocks,
            List<String> metrics,
            DateRange period,
            long count,
            String summary,
            List<Limitation> limitations
    ) {
        if (count > 0) {
            limitations.add(limitation(
                    issue,
                    "WARNING",
                    blocks,
                    metrics,
                    period,
                    Math.toIntExact(count),
                    summary
            ));
        }
    }

    private Limitation limitation(
            Issue issue,
            String severity,
            List<String> blocks,
            List<String> metrics,
            DateRange period,
            int count,
            String summary
    ) {
        return new Limitation(
                issue.limitationId(),
                issue.code(),
                severity,
                "STORE",
                null,
                blocks,
                metrics,
                period,
                count,
                summary,
                null,
                List.of()
        );
    }

    private String message(ReportState state, int warningCount) {
        return switch (state) {
            case READY -> "Данные готовы";
            case PARTIAL -> "Основные показатели готовы; ограничений: " + warningCount;
            case BLOCKED -> "Нельзя достоверно рассчитать завершённую неделю";
            default -> "Отчёт готовится";
        };
    }

    private record Issue(String limitationId, String code) {

        private Issue {
            requireNonNull(limitationId, "limitationId");
            requireNonNull(code, "code");
        }
    }

    public record Decision(
            ReportState reportState,
            List<SourceCoverage> sourceCoverage,
            List<Limitation> limitations,
            QualitySummary qualitySummary
    ) {

        public Decision {
            requireNonNull(reportState, "reportState");
            sourceCoverage = List.copyOf(requireNonNull(sourceCoverage, "sourceCoverage"));
            limitations = List.copyOf(requireNonNull(limitations, "limitations"));
            requireNonNull(qualitySummary, "qualitySummary");
        }
    }
}
