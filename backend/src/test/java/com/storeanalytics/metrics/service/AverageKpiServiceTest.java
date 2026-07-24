package com.storeanalytics.metrics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeanalytics.metrics.exception.StoreNotFoundException;
import com.storeanalytics.metrics.repository.AverageKpiAggregate;
import com.storeanalytics.metrics.repository.AverageKpiRepository;
import com.storeanalytics.store.repository.StoreRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AverageKpiServiceTest {

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 7, 10);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 7, 12);
    private static final LocalDate PREVIOUS_START = LocalDate.of(2026, 7, 7);
    private static final LocalDate PREVIOUS_END = LocalDate.of(2026, 7, 9);

    private StoreRepository storeRepository;
    private AverageKpiRepository averageKpiRepository;
    private AverageKpiService service;

    @BeforeEach
    void setUp() {
        storeRepository = mock(StoreRepository.class);
        averageKpiRepository = mock(AverageKpiRepository.class);
        service = new AverageKpiService(storeRepository, averageKpiRepository);
    }

    @Test
    void calculatesAveragesAndDynamicsFromUnroundedValues() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.existsById(storeId)).thenReturn(true);
        when(averageKpiRepository.aggregate(
                storeId, PERIOD_START, PERIOD_END, PREVIOUS_START, PREVIOUS_END
        )).thenReturn(List.of(aggregate(
                values("100.00", "3.000", "1000.00", 3, "500.00", "2.000"),
                values("90.00", "2.000", "600.00", 2, "200.00", "1.000")
        )));

        AverageKpiResult result = service.calculate(storeId, period());

        assertThat(result.formulaVersion()).isEqualTo("average-kpi-v1");
        assertThat(result.previousPeriodStart()).isEqualTo(PREVIOUS_START);
        assertThat(result.previousPeriodEnd()).isEqualTo(PREVIOUS_END);
        assertComparison(
                result.averageReceipt(),
                expected("1000.00", "3", "333"),
                expected("600.00", "2", "300"),
                "11.1"
        );
        assertComparison(
                result.additionalRevenuePerPhone(),
                expected("500.00", "2.000", "250"),
                expected("200.00", "1.000", "200"),
                "25.0"
        );
        assertComparison(
                result.categoryAveragePrices().getFirst().averageUnitPrice(),
                expected("100.00", "3.000", "33"),
                expected("90.00", "2.000", "45"),
                "-25.9"
        );
        verify(averageKpiRepository).aggregate(
                storeId, PERIOD_START, PERIOD_END, PREVIOUS_START, PREVIOUS_END
        );
    }

    @Test
    void returnsNullValueAndChangeForNonpositiveDenominators() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.existsById(storeId)).thenReturn(true);
        when(averageKpiRepository.aggregate(
                storeId, PERIOD_START, PERIOD_END, PREVIOUS_START, PREVIOUS_END
        )).thenReturn(List.of(aggregate(
                values("10.00", "0.000", "10.00", 0, "10.00", "0.000"),
                values("10.00", "-1.000", "10.00", 0, "10.00", "-1.000")
        )));

        AverageKpiResult result = service.calculate(storeId, period());

        assertThat(result.averageReceipt().current().value()).isNull();
        assertThat(result.averageReceipt().changePercent()).isNull();
        assertThat(result.additionalRevenuePerPhone().current().value()).isNull();
        assertThat(result.additionalRevenuePerPhone().changePercent()).isNull();
        assertThat(result.categoryAveragePrices().getFirst().averageUnitPrice().current().value())
                .isNull();
        assertThat(result.categoryAveragePrices().getFirst().averageUnitPrice().changePercent())
                .isNull();
    }

    @Test
    void returnsNullChangeWhenPreviousAverageIsZero() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.existsById(storeId)).thenReturn(true);
        when(averageKpiRepository.aggregate(
                storeId, PERIOD_START, PERIOD_END, PREVIOUS_START, PREVIOUS_END
        )).thenReturn(List.of(aggregate(
                values("10.00", "1.000", "10.00", 1, "10.00", "1.000"),
                values("0.00", "1.000", "0.00", 1, "0.00", "1.000")
        )));

        AverageKpiResult result = service.calculate(storeId, period());

        assertThat(result.averageReceipt().previous().value()).isEqualByComparingTo("0");
        assertThat(result.averageReceipt().changePercent()).isNull();
        assertThat(result.additionalRevenuePerPhone().changePercent()).isNull();
        assertThat(result.categoryAveragePrices().getFirst().averageUnitPrice().changePercent())
                .isNull();
    }

    @Test
    void rejectsUnknownStoreBeforeRunningAverageQuery() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.existsById(storeId)).thenReturn(false);

        assertThatThrownBy(() -> service.calculate(storeId, period()))
                .isInstanceOf(StoreNotFoundException.class)
                .hasMessageContaining(storeId.toString());
        verifyNoInteractions(averageKpiRepository);
    }

    private AverageKpiAggregate aggregate(
            AverageValues current,
            AverageValues previous
    ) {
        return new AverageKpiAggregate(
                "CHARGER_CABLE",
                "CHARGER_CABLE",
                true,
                decimal(current.categoryRevenue()),
                decimal(current.categoryQuantity()),
                decimal(previous.categoryRevenue()),
                decimal(previous.categoryQuantity()),
                decimal(current.netRevenue()),
                current.receiptCount(),
                decimal(current.additionalRevenue()),
                decimal(current.phoneQuantity()),
                decimal(previous.netRevenue()),
                previous.receiptCount(),
                decimal(previous.additionalRevenue()),
                decimal(previous.phoneQuantity())
        );
    }

    private void assertComparison(
            AverageMetricComparison comparison,
            ExpectedSnapshot current,
            ExpectedSnapshot previous,
            String changePercent
    ) {
        assertThat(comparison.current().numerator())
                .isEqualByComparingTo(current.numerator());
        assertThat(comparison.current().denominator())
                .isEqualByComparingTo(current.denominator());
        assertThat(comparison.current().value()).isEqualByComparingTo(current.value());
        assertThat(comparison.previous().numerator())
                .isEqualByComparingTo(previous.numerator());
        assertThat(comparison.previous().denominator())
                .isEqualByComparingTo(previous.denominator());
        assertThat(comparison.previous().value()).isEqualByComparingTo(previous.value());
        assertThat(comparison.changePercent()).isEqualByComparingTo(changePercent);
    }

    private AverageValues values(
            String categoryRevenue,
            String categoryQuantity,
            String netRevenue,
            long receiptCount,
            String additionalRevenue,
            String phoneQuantity
    ) {
        return new AverageValues(
                categoryRevenue,
                categoryQuantity,
                netRevenue,
                receiptCount,
                additionalRevenue,
                phoneQuantity
        );
    }

    private ExpectedSnapshot expected(String numerator, String denominator, String value) {
        return new ExpectedSnapshot(numerator, denominator, value);
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private StoreKpiPeriod period() {
        return new StoreKpiPeriod(PERIOD_START, PERIOD_END);
    }

    private record AverageValues(
            String categoryRevenue,
            String categoryQuantity,
            String netRevenue,
            long receiptCount,
            String additionalRevenue,
            String phoneQuantity
    ) {
    }

    private record ExpectedSnapshot(
            String numerator,
            String denominator,
            String value
    ) {
    }
}
