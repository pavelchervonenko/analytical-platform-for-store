package com.storeanalytics.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.metrics.service.AttachRateEntry;
import com.storeanalytics.metrics.service.AttachRateResult;
import com.storeanalytics.metrics.service.CategoryKpiResult;
import com.storeanalytics.metrics.service.StoreKpiResult;
import com.storeanalytics.performance.service.EmployeeRatingResult;
import com.storeanalytics.product.model.AttachDenominatorCode;
import com.storeanalytics.salary.service.PayrollRunDetailView;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnnualReportAggregationServiceTest {

    private final AnnualReportAggregationService service =
            new AnnualReportAggregationService();

    @Test
    void recalculatesRatiosFromAnnualNumeratorsInsteadOfAveragingMonthlyPercentages() {
        MonthlyReportPayload first = month(
                new BigDecimal("100.00"),
                new BigDecimal("50.00"),
                BigDecimal.ONE,
                BigDecimal.ONE
        );
        MonthlyReportPayload second = month(
                new BigDecimal("900.00"),
                new BigDecimal("90.00"),
                new BigDecimal("9.00"),
                new BigDecimal("99.00")
        );

        AnnualReportAggregate result = service.aggregate(List.of(first, second));

        assertThat(result.totals().netRevenue()).isEqualByComparingTo("1000.00");
        assertThat(result.totals().grossProfit()).isEqualByComparingTo("140.00");
        assertThat(result.totals().marginPercent()).isEqualByComparingTo("14.00");
        assertThat(result.attachRates()).singleElement().satisfies(rate -> {
            assertThat(rate.formulaVersion()).isEqualTo("attach-rate-v2");
            assertThat(rate.numeratorReceiptCount()).isEqualByComparingTo("10.00");
            assertThat(rate.denominatorReceiptCount()).isEqualByComparingTo("100.00");
            assertThat(rate.ratePerHundred()).isEqualByComparingTo("10.00");
        });
    }

    private MonthlyReportPayload month(
            BigDecimal revenue,
            BigDecimal grossProfit,
            BigDecimal attachNumerator,
            BigDecimal attachDenominator
    ) {
        UUID storeId = UUID.randomUUID();
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate through = LocalDate.of(2026, 1, 31);
        StoreKpiResult storeKpi = new StoreKpiResult(
                storeId,
                from,
                through,
                "store-kpi-v1",
                revenue,
                BigDecimal.ONE,
                revenue.subtract(grossProfit),
                grossProfit,
                grossProfit.multiply(BigDecimal.valueOf(100)).divide(revenue),
                null
        );
        AttachRateResult attachRates = new AttachRateResult(
                storeId,
                from,
                through,
                "attach-rate-v2",
                null,
                List.of(new AttachRateEntry(
                        "ACCESSORIES_PER_PHONE",
                        "ACCESSORY",
                        AttachDenominatorCode.PHONE,
                        attachNumerator,
                        attachDenominator,
                        attachNumerator.multiply(BigDecimal.valueOf(100))
                                .divide(attachDenominator, 2, java.math.RoundingMode.HALF_UP)
                ))
        );
        EmployeeRatingResult rating = mock(EmployeeRatingResult.class);
        when(rating.employees()).thenReturn(List.of());
        PayrollRunDetailView payroll = mock(PayrollRunDetailView.class);
        when(payroll.statements()).thenReturn(List.of());
        return new MonthlyReportPayload(
                1,
                null,
                storeKpi,
                new CategoryKpiResult(
                        storeId, from, through, "category-kpi-v1", List.of(), List.of()
                ),
                null,
                attachRates,
                null,
                rating,
                payroll,
                null
        );
    }
}

