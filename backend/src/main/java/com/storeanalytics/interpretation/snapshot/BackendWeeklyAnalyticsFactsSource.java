package com.storeanalytics.interpretation.snapshot;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.metrics.service.AverageKpiService;
import com.storeanalytics.performance.service.StorePlanProgressService;
import com.storeanalytics.performance.service.StorePlanProgressView;
import com.storeanalytics.store.service.StoreDataStatusService;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BackendWeeklyAnalyticsFactsSource implements WeeklyAnalyticsFactsSource {

    private final WeeklyMetricFactsReader metricFactsReader;
    private final AverageKpiService averageKpiService;
    private final StorePlanProgressService planProgressService;
    private final StoreDataStatusService dataStatusService;

    public BackendWeeklyAnalyticsFactsSource(
            WeeklyMetricFactsReader metricFactsReader,
            AverageKpiService averageKpiService,
            StorePlanProgressService planProgressService,
            StoreDataStatusService dataStatusService
    ) {
        this.metricFactsReader = metricFactsReader;
        this.averageKpiService = averageKpiService;
        this.planProgressService = planProgressService;
        this.dataStatusService = dataStatusService;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WeeklyAnalyticsFacts load(WeeklyAnalyticsFactsQuery query) {
        WeeklyAnalyticsFactsQuery validated = requireNonNull(query, "query");
        UUID storeId = validated.storeId();
        WeeklyPeriodFacts current = metricFactsReader.read(storeId, validated.period());
        WeeklyPeriodFacts previous = metricFactsReader.read(
                storeId, validated.comparisonPeriod()
        );
        return new WeeklyAnalyticsFacts(
                storeId,
                validated,
                dataStatusService.get(storeId),
                current,
                previous,
                averageKpiService.calculate(storeId, validated.period()),
                planContexts(storeId, validated.period().start(), validated.period().end())
        );
    }

    private List<StorePlanProgressView> planContexts(
            UUID storeId,
            LocalDate periodStart,
            LocalDate periodEnd
    ) {
        List<StorePlanProgressView> result = new ArrayList<>();
        YearMonth month = YearMonth.from(periodStart);
        YearMonth lastMonth = YearMonth.from(periodEnd);
        while (!month.isAfter(lastMonth)) {
            LocalDate asOf = month.equals(lastMonth)
                    ? periodEnd
                    : month.atEndOfMonth();
            planProgressService.find(storeId, month, asOf).ifPresent(result::add);
            month = month.plusMonths(1);
        }
        return List.copyOf(result);
    }
}
