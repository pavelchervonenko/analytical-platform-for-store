package com.storeanalytics.metrics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeanalytics.metrics.exception.StoreNotFoundException;
import com.storeanalytics.metrics.repository.StoreKpiAggregate;
import com.storeanalytics.metrics.repository.StoreKpiRepository;
import com.storeanalytics.store.repository.StoreRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StoreKpiServiceTest {

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 7, 1);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 7, 31);

    private StoreRepository storeRepository;
    private StoreKpiRepository storeKpiRepository;
    private StoreKpiService service;

    @BeforeEach
    void setUp() {
        storeRepository = mock(StoreRepository.class);
        storeKpiRepository = mock(StoreKpiRepository.class);
        service = new StoreKpiService(storeRepository, storeKpiRepository);
    }

    @Test
    void calculatesProfitAndMarginFromCompleteData() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.existsById(storeId)).thenReturn(true);
        when(storeKpiRepository.aggregate(storeId, PERIOD_START, PERIOD_END))
                .thenReturn(aggregate(
                        "300.00", "3.000", "200.00",
                        new QualityCounts(2, 0, 0, 1, 1)
                ));

        StoreKpiResult result = service.calculate(storeId, period());

        assertThat(result.formulaVersion()).isEqualTo("store-kpi-v1");
        assertThat(result.netRevenue()).isEqualByComparingTo("300.00");
        assertThat(result.netQuantity()).isEqualByComparingTo("3.000");
        assertThat(result.costAmount()).isEqualByComparingTo("200.00");
        assertThat(result.grossProfit()).isEqualByComparingTo("100.00");
        assertThat(result.marginPercent()).isEqualByComparingTo("33.33");
        assertThat(result.dataQuality().completeCostData()).isTrue();
        assertThat(result.dataQuality().unexpectedZeroCostItemCount()).isOne();
        assertThat(result.dataQuality().storeOpenQualityIssueCount()).isOne();
    }

    @Test
    void doesNotPresentPartialCostAsComplete() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.existsById(storeId)).thenReturn(true);
        when(storeKpiRepository.aggregate(storeId, PERIOD_START, PERIOD_END))
                .thenReturn(aggregate(
                        "250.00", "4.500", "125.00",
                        new QualityCounts(5, 1, 1, 1, 2)
                ));

        StoreKpiResult result = service.calculate(storeId, period());

        assertThat(result.netRevenue()).isEqualByComparingTo("250.00");
        assertThat(result.costAmount()).isNull();
        assertThat(result.grossProfit()).isNull();
        assertThat(result.marginPercent()).isNull();
        assertThat(result.dataQuality().completeCostData()).isFalse();
        assertThat(result.dataQuality().missingCostItemCount()).isOne();
        assertThat(result.dataQuality().unmappedItemCount()).isOne();
    }

    @Test
    void marginIsAbsentWhenNetRevenueIsZero() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.existsById(storeId)).thenReturn(true);
        when(storeKpiRepository.aggregate(storeId, PERIOD_START, PERIOD_END))
                .thenReturn(aggregate(
                        "0.00", "0.000", "0.00",
                        new QualityCounts(0, 0, 0, 0, 0)
                ));

        StoreKpiResult result = service.calculate(storeId, period());

        assertThat(result.costAmount()).isEqualByComparingTo("0.00");
        assertThat(result.grossProfit()).isEqualByComparingTo("0.00");
        assertThat(result.marginPercent()).isNull();
    }

    @Test
    void rejectsUnknownStoreBeforeRunningAnalyticsQuery() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.existsById(storeId)).thenReturn(false);

        assertThatThrownBy(() -> service.calculate(storeId, period()))
                .isInstanceOf(StoreNotFoundException.class)
                .hasMessageContaining(storeId.toString());
        verifyNoInteractions(storeKpiRepository);
    }

    @Test
    void rejectsReversedPeriod() {
        assertThatThrownBy(() -> new StoreKpiPeriod(PERIOD_END, PERIOD_START))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private StoreKpiPeriod period() {
        return new StoreKpiPeriod(PERIOD_START, PERIOD_END);
    }

    private StoreKpiAggregate aggregate(
            String netRevenue,
            String netQuantity,
            String costAmount,
            QualityCounts counts
    ) {
        return new StoreKpiAggregate(
                new BigDecimal(netRevenue),
                new BigDecimal(netQuantity),
                new BigDecimal(costAmount),
                counts.includedItems(),
                counts.unmappedItems(),
                counts.missingCostItems(),
                counts.unexpectedZeroCostItems(),
                counts.openIssues()
        );
    }

    private record QualityCounts(
            long includedItems,
            long unmappedItems,
            long missingCostItems,
            long unexpectedZeroCostItems,
            long openIssues
    ) {
    }
}
