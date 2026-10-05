package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse.CoverageState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.DateRange;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Limitation;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.QualitySummary;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.ReportState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.SourceCode;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.SourceCoverage;
import com.storeanalytics.metrics.service.SellerPeriodMetrics;
import com.storeanalytics.metrics.service.SellerReturnAttributionQuality;
import com.storeanalytics.metrics.service.StoreKpiResult;
import com.storeanalytics.store.service.StoreDataStatusView;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Routes data quality to the exact weekly-review metrics it can affect. */
public final class WeeklyReviewQualityPolicyV1 {

    /** Requires continuous source coverage and selected-seller item quality for v3 facts. */
    Decision decideSellers(
            SellerWeeklySourceCoverage source,
            SellerPeriodMetrics current,
            SellerPeriodMetrics previous,
            AttributionWindows attribution,
            DateRange currentPeriod,
            DateRange previousPeriod,
            SellerWeeklySourceStability sourceStability
    ) {
        SellerWeeklySourceCoverage sourceWindows = requireNonNull(source, "source");
        SellerPeriodMetrics currentMetrics = requireNonNull(current, "current");
        SellerPeriodMetrics previousMetrics = requireNonNull(previous, "previous");
        DateRange currentRange = requireNonNull(currentPeriod, "currentPeriod");
        DateRange previousRange = requireNonNull(previousPeriod, "previousPeriod");
        AttributionWindows returnWindows = requireNonNull(attribution, "attribution");
        List<SourceCoverage> coverage = List.of(
                sellerCoverage(SourceCode.SALES, sourceWindows.sales(), currentRange, previousRange),
                sellerCoverage(SourceCode.RETURNS, sourceWindows.returns(), currentRange, previousRange)
        );
        List<Limitation> limitations = new ArrayList<>();
        coverage.stream().filter(item -> item.state() != CoverageState.COMPLETE)
                .map(item -> sellerSourceLimitation(item, currentRange, previousRange))
                .forEach(limitations::add);
        boolean ordersIncomplete = !sourceWindows.orders().current()
                || !sourceWindows.orders().previous();
        if (ordersIncomplete) {
            limitations.add(ordersCoverageLimitation(sourceWindows.orders(),
                    currentRange, previousRange));
        }
        boolean unstable = addSellerSourceStabilityLimitation(
                requireNonNull(sourceStability, "sourceStability"), currentRange, limitations);
        boolean blocked = coverage.stream().anyMatch(item -> item.state() != CoverageState.COMPLETE)
                || ordersIncomplete || unstable;
        if (!blocked) {
            addSellerLimitations(currentMetrics, currentRange, "current", limitations);
            addSellerLimitations(previousMetrics, previousRange, "previous", limitations);
            addSellerReturnAttributionLimitations(
                    returnWindows.current(), currentRange, "current", limitations);
            addSellerReturnAttributionLimitations(
                    returnWindows.previous(), previousRange, "previous", limitations);
        }
        return decision(blocked, coverage, limitations);
    }

    private SourceCoverage sellerCoverage(
            SourceCode code, SellerWeeklySourceCoverage.Window window,
            DateRange current, DateRange previous
    ) {
        CoverageState state = window.current() && window.previous() ? CoverageState.COMPLETE
                : window.current() || window.previous() ? CoverageState.PARTIAL
                : CoverageState.MISSING;
        return new SourceCoverage(code, true, sellerSourceAffectedBlocks(),
                window.current() ? current.end() : null,
                window.previous() ? previous.end() : null, state,
                state == CoverageState.COMPLETE ? null
                        : sourceLabel(code) + " не покрывают обе сравниваемые недели непрерывно");
    }

    private Limitation sellerSourceLimitation(
            SourceCoverage coverage, DateRange current, DateRange previous
    ) {
        Limitation item = sourceLimitation(coverage, current, previous);
        return new Limitation(item.limitationId(), item.code(), "BLOCKING", "SELLERS", null,
                sellerSourceAffectedBlocks(), item.affectedMetricCodes(), item.period(),
                item.affectedCount(), item.summary(), item.resolution(), item.evidenceRefs());
    }

    private Limitation ordersCoverageLimitation(
            SellerWeeklySourceCoverage.Window window, DateRange current, DateRange previous
    ) {
        return new Limitation("source:orders", "ORDERS_COVERAGE_INCOMPLETE", "BLOCKING",
                "SELLERS", null, sellerSourceAffectedBlocks(),
                List.of("SALES_REVENUE", "SALE_DOCUMENT_COUNT", "NET_REVENUE",
                        "GROSS_PROFIT", "SALES_STRUCTURE", "ADDITIONAL_REVENUE", "ATTACH"),
                window.current() ? previous : current, 1,
                "Позиции заказов не покрывают обе сравниваемые недели непрерывно", null, List.of());
    }

    private boolean addSellerSourceStabilityLimitation(
            SellerWeeklySourceStability stability, DateRange period, List<Limitation> limitations
    ) {
        if (stability == SellerWeeklySourceStability.STABLE) {
            return false;
        }
        boolean syncing = stability == SellerWeeklySourceStability.IN_PROGRESS;
        limitations.add(new Limitation(
                syncing ? "source:sync-in-progress" : "source:reconciliation-pending",
                syncing ? "SOURCE_SYNC_IN_PROGRESS" : "SOURCE_RECONCILIATION_PENDING",
                "BLOCKING", "SELLERS", null, sellerSourceAffectedBlocks(),
                List.of("SALES_REVENUE", "RETURN_REVENUE", "NET_REVENUE",
                        "GROSS_PROFIT", "SALES_STRUCTURE", "ADDITIONAL_REVENUE", "ATTACH"),
                period, 1,
                syncing ? "Синхронизация ещё идёт; недельные данные могут измениться"
                        : "Последняя синхронизация завершилась ошибкой; полнота данных не подтверждена",
                null, List.of()));
        return true;
    }

    private void addSellerLimitations(
            SellerPeriodMetrics metrics,
            DateRange period,
            String suffix,
            List<Limitation> limitations
    ) {
        addSellerCountLimitation(new Issue("classification:" + suffix, "PRODUCTS_UNCLASSIFIED"),
                List.of("sales-structure", "additional-sales"),
                List.of("SALES_STRUCTURE", "ADDITIONAL_REVENUE", "ATTACH"), period,
                metrics.unmappedItemCount(), "Часть товарных позиций продавцов не классифицирована",
                limitations);
        addSellerCountLimitation(new Issue("cost:missing:" + suffix, "COST_DATA_MISSING"),
                List.of("results"), List.of("GROSS_PROFIT", "MARGIN_PERCENT"), period,
                metrics.totals().dataQuality().missingCostItemCount(),
                "Для части позиций продавцов отсутствует себестоимость", limitations);
    }

    private void addSellerReturnAttributionLimitations(
            SellerReturnAttributionQuality quality,
            DateRange period,
            String suffix,
            List<Limitation> limitations
    ) {
        addSellerCountLimitation(new Issue("attribution:missing-return-author:" + suffix,
                        "RETURN_EMPLOYEE_MISSING"),
                List.of("results", "sales-structure", "additional-sales", "team", "employees"),
                List.of("RETURN_REVENUE", "NET_REVENUE", "GROSS_PROFIT", "MARGIN_PERCENT",
                        "ADDITIONAL_REVENUE", "ADDITIONAL_SHARE", "SALES_STRUCTURE", "ATTACH"),
                period, quality.missingReturnEmployeeCount(),
                "У части возвратов не указан сотрудник LiveSklad; их вклад в показатели продавцов неизвестен",
                limitations);
        addSellerCountLimitation(new Issue("attribution:unresolved-return-author:" + suffix,
                        "RETURN_EMPLOYEE_UNRESOLVED"),
                List.of("results", "sales-structure", "additional-sales", "team", "employees"),
                List.of("RETURN_REVENUE", "NET_REVENUE", "GROSS_PROFIT", "MARGIN_PERCENT",
                        "ADDITIONAL_REVENUE", "ADDITIONAL_SHARE", "SALES_STRUCTURE", "ATTACH"),
                period, quality.unresolvedReturnEmployeeCount(),
                "Сотрудника части возвратов LiveSklad не удалось определить; "
                        + "их вклад в показатели продавцов неизвестен",
                limitations);
    }

    private void addSellerCountLimitation(
            Issue issue,
            List<String> blocks,
            List<String> metricCodes,
            DateRange period,
            long count,
            String summary,
            List<Limitation> limitations
    ) {
        if (count > 0) {
            limitations.add(new Limitation(issue.limitationId(), issue.code(), "WARNING",
                    "SELLERS", null, blocks, metricCodes, period, Math.toIntExact(count),
                    summary, null, List.of()));
        }
    }

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
        addSourceLimitations(coverage, currentRange, previousRange, limitations);
        boolean blocked = sourceBlocked(coverage, currentRange);
        if (!blocked) {
            addClassificationLimitations(
                    currentKpi, previousKpi, currentRange, previousRange, limitations
            );
            addMissingCostLimitations(
                    currentKpi, previousKpi, currentRange, previousRange, limitations
            );
            addConsistencyLimitations(
                    currentKpi, previousKpi, currentRange, previousRange, limitations
            );
        }

        return decision(blocked, coverage, limitations);
    }

    private void addSourceLimitations(
            List<SourceCoverage> coverage,
            DateRange current,
            DateRange previous,
            List<Limitation> limitations
    ) {
        coverage.stream()
                .filter(SourceCoverage::requiredForReport)
                .filter(item -> item.state() != CoverageState.COMPLETE)
                .map(item -> sourceLimitation(item, current, previous))
                .forEach(limitations::add);
    }

    private boolean sourceBlocked(List<SourceCoverage> coverage, DateRange current) {
        return coverage.stream().filter(SourceCoverage::requiredForReport).anyMatch(item ->
                item.state() == CoverageState.MISSING
                        || item.state() == CoverageState.PARTIAL
                        && item.currentThroughDate() != null
                        && item.currentThroughDate().isBefore(current.end()));
    }

    private Decision decision(
            boolean blocked,
            List<SourceCoverage> coverage,
            List<Limitation> limitations
    ) {
        ReportState reportState = blocked ? ReportState.BLOCKED
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
                complete ? null : "Связь с сотрудниками доступна не для всех возвратов"
        );
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
                sourceAffectedBlocks(),
                through,
                through,
                state,
                state == CoverageState.COMPLETE
                        ? null
                        : sourceLabel(sourceCode)
                                + " не покрывают обе сравниваемые недели"
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
                sourceAffectedBlocks(),
                affectedMetrics(coverage.sourceCode()),
                currentMissing ? current : previous,
                1,
                currentMissing
                        ? sourceLabel(coverage.sourceCode())
                                + " не покрывают завершённую неделю"
                        : sourceLabel(coverage.sourceCode())
                                + " не покрывают неделю сравнения"
        );
    }

    private List<String> affectedMetrics(SourceCode sourceCode) {
        return switch (sourceCode) {
            case SALES -> List.of(
                    "SALES_REVENUE",
                    "SALE_DOCUMENT_COUNT",
                    "AVERAGE_SALE",
                    "NET_REVENUE",
                    "GROSS_PROFIT",
                    "MARGIN_PERCENT"
            );
            case RETURNS -> List.of(
                    "RETURN_REVENUE",
                    "RETURN_DOCUMENT_COUNT",
                    "NET_REVENUE",
                    "GROSS_PROFIT",
                    "MARGIN_PERCENT"
            );
            default -> List.of();
        };
    }

    private List<String> sourceAffectedBlocks() {
        return List.of(
                "summary",
                "results",
                "revenue-decomposition",
                "sales-structure",
                "team",
                "employees"
        );
    }

    private List<String> sellerSourceAffectedBlocks() {
        return List.of("summary", "results", "revenue-decomposition", "sales-structure",
                "additional-sales", "team", "employees");
    }

    private String sourceLabel(SourceCode sourceCode) {
        return switch (sourceCode) {
            case SALES -> "Данные о продажах";
            case RETURNS -> "Данные о возвратах";
            default -> "Данные источника";
        };
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

    private void addMissingCostLimitations(
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
                List.of("results", "summary", "revenue-decomposition"),
                List.of("NET_REVENUE"),
                currentPeriod,
                current.dataQuality().periodOpenConsistencyIssueCount(),
                "Есть проблемы согласованности продаж или возвратов недели",
                limitations
        );
        addCountLimitation(
                new Issue("consistency:previous", "SALES_OR_RETURNS_CONSISTENCY_ISSUE"),
                List.of("results", "summary", "revenue-decomposition"),
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

    record AttributionWindows(SellerReturnAttributionQuality current,
            SellerReturnAttributionQuality previous) {

        AttributionWindows {
            requireNonNull(current, "current");
            requireNonNull(previous, "previous");
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
