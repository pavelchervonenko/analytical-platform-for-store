package com.storeanalytics.interpretation.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeanalytics.metrics.service.AverageKpiResult;
import com.storeanalytics.metrics.service.AverageKpiService;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.performance.service.StorePlanProgressService;
import com.storeanalytics.performance.service.StorePlanProgressView;
import com.storeanalytics.store.service.StoreDataStatusService;
import com.storeanalytics.store.service.StoreDataStatusView;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BackendWeeklyAnalyticsFactsSourceTest {

    @Test
    void readsBothWeeksAndEachBoundaryMonthInOneTypedResult() {
        UUID storeId = UUID.randomUUID();
        StoreKpiPeriod currentPeriod = period("2026-07-27", "2026-08-02");
        StoreKpiPeriod previousPeriod = period("2026-07-20", "2026-07-26");
        WeeklyAnalyticsFactsQuery query = new WeeklyAnalyticsFactsQuery(
                storeId, currentPeriod, previousPeriod
        );
        WeeklyMetricFactsReader metricReader = mock(WeeklyMetricFactsReader.class);
        AverageKpiService averageService = mock(AverageKpiService.class);
        StorePlanProgressService planService = mock(StorePlanProgressService.class);
        StoreDataStatusService dataStatusService = mock(StoreDataStatusService.class);
        WeeklyPeriodFacts current = mock(WeeklyPeriodFacts.class);
        WeeklyPeriodFacts previous = mock(WeeklyPeriodFacts.class);
        AverageKpiResult averages = mock(AverageKpiResult.class);
        StoreDataStatusView status = mock(StoreDataStatusView.class);
        StorePlanProgressView julyPlan = mock(StorePlanProgressView.class);
        StorePlanProgressView augustPlan = mock(StorePlanProgressView.class);

        when(metricReader.read(storeId, currentPeriod)).thenReturn(current);
        when(metricReader.read(storeId, previousPeriod)).thenReturn(previous);
        when(averageService.calculate(storeId, currentPeriod)).thenReturn(averages);
        when(dataStatusService.get(storeId)).thenReturn(status);
        when(planService.find(
                storeId, YearMonth.of(2026, 7), LocalDate.of(2026, 7, 31)
        )).thenReturn(Optional.of(julyPlan));
        when(planService.find(
                storeId, YearMonth.of(2026, 8), LocalDate.of(2026, 8, 2)
        )).thenReturn(Optional.of(augustPlan));

        BackendWeeklyAnalyticsFactsSource source = new BackendWeeklyAnalyticsFactsSource(
                metricReader, averageService, planService, dataStatusService
        );
        WeeklyAnalyticsFacts facts = source.load(query);

        assertThat(facts.storeId()).isEqualTo(storeId);
        assertThat(facts.query()).isSameAs(query);
        assertThat(facts.current()).isSameAs(current);
        assertThat(facts.previous()).isSameAs(previous);
        assertThat(facts.averageComparisons()).isSameAs(averages);
        assertThat(facts.sourceDataStatus()).isSameAs(status);
        assertThat(facts.planContexts()).containsExactly(julyPlan, augustPlan);
        verify(metricReader).read(storeId, currentPeriod);
        verify(metricReader).read(storeId, previousPeriod);
    }

    private StoreKpiPeriod period(String start, String end) {
        return new StoreKpiPeriod(LocalDate.parse(start), LocalDate.parse(end));
    }
}
