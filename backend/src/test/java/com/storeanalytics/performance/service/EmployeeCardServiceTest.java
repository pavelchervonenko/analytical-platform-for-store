package com.storeanalytics.performance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.salary.service.PayrollManagementService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EmployeeCardServiceTest {

    private EmployeeRatingQueryService ratingService;
    private PayrollManagementService payrollService;
    private EmployeeCardService service;

    @BeforeEach
    void setUp() {
        ratingService = mock(EmployeeRatingQueryService.class);
        payrollService = mock(PayrollManagementService.class);
        service = new EmployeeCardService(ratingService, payrollService);
    }

    @Test
    void comparesWithImmediatelyPrecedingPeriodOfEqualLength() {
        UUID storeId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        StoreKpiPeriod currentPeriod = new StoreKpiPeriod(
                LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 20)
        );
        StoreKpiPeriod previousPeriod = new StoreKpiPeriod(
                LocalDate.of(2026, 6, 29), LocalDate.of(2026, 7, 9)
        );
        EmployeeRatingEntry current = entry(employeeId, 1, "110.00", "1200.00");
        EmployeeRatingEntry previous = entry(employeeId, 3, "90.00", "900.00");
        EmployeeRatingResult currentResult = result(storeId, currentPeriod, current);
        EmployeeRatingResult previousResult = result(storeId, previousPeriod, previous);
        when(ratingService.get(storeId, currentPeriod)).thenReturn(currentResult);
        when(ratingService.get(storeId, previousPeriod)).thenReturn(previousResult);

        EmployeeCardView card = service.card(storeId, employeeId, currentPeriod);

        assertThat(card.previousPeriodStart()).isEqualTo(previousPeriod.start());
        assertThat(card.previousPeriodEnd()).isEqualTo(previousPeriod.end());
        assertThat(card.dynamics().rankImprovement()).isEqualTo(2);
        assertThat(card.dynamics().overallScoreChange()).isEqualByComparingTo("20.00");
        assertThat(card.dynamics().revenueChange()).isEqualByComparingTo("300.00");
        assertThat(card.payroll()).isNull();
        verifyNoInteractions(payrollService);
    }

    @Test
    void comparesCommercialSharesAndAttachRatesWithPreviousWeek() {
        UUID storeId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        StoreKpiPeriod currentPeriod = new StoreKpiPeriod(
                LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 16)
        );
        StoreKpiPeriod previousPeriod = new StoreKpiPeriod(
                LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 9)
        );
        EmployeeRatingEntry current = entry(employeeId, 1, "110.00", "1200.00");
        EmployeeRatingEntry previous = entry(employeeId, 2, "100.00", "1000.00");
        when(current.accessorySharePercent()).thenReturn(new BigDecimal("6.30"));
        when(previous.accessorySharePercent()).thenReturn(new BigDecimal("5.10"));
        when(current.serviceSharePercent()).thenReturn(new BigDecimal("4.20"));
        when(previous.serviceSharePercent()).thenReturn(new BigDecimal("3.80"));
        when(current.additionalSharePercent()).thenReturn(new BigDecimal("10.50"));
        when(previous.additionalSharePercent()).thenReturn(new BigDecimal("8.90"));

        EmployeeAttachRatingEntry currentAttach = mock(EmployeeAttachRatingEntry.class);
        EmployeeAttachRatingEntry previousAttach = mock(EmployeeAttachRatingEntry.class);
        when(currentAttach.metricCode()).thenReturn("CASE_APPLE_IPHONE");
        when(currentAttach.ratePercent()).thenReturn(new BigDecimal("125.00"));
        when(previousAttach.metricCode()).thenReturn("CASE_APPLE_IPHONE");
        when(previousAttach.ratePercent()).thenReturn(new BigDecimal("75.00"));
        when(current.attachRates()).thenReturn(List.of(currentAttach));
        when(previous.attachRates()).thenReturn(List.of(previousAttach));
        when(ratingService.get(storeId, currentPeriod))
                .thenReturn(result(storeId, currentPeriod, current));
        when(ratingService.get(storeId, previousPeriod))
                .thenReturn(result(storeId, previousPeriod, previous));

        EmployeeRatingDynamics dynamics = service.card(
                storeId, employeeId, currentPeriod, EmployeeComparisonMode.PREVIOUS_WEEK
        ).dynamics();

        assertThat(dynamics.accessoryShareChange()).isEqualByComparingTo("1.20");
        assertThat(dynamics.serviceShareChange()).isEqualByComparingTo("0.40");
        assertThat(dynamics.additionalShareChange()).isEqualByComparingTo("1.60");
        assertThat(dynamics.attachRateChanges()).singleElement().satisfies(change -> {
            assertThat(change.metricCode()).isEqualTo("CASE_APPLE_IPHONE");
            assertThat(change.previousRate()).isEqualByComparingTo("75.00");
            assertThat(change.currentRate()).isEqualByComparingTo("125.00");
            assertThat(change.change()).isEqualByComparingTo("50.00");
        });
    }

    @Test
    void alignsPartialWeeklyComparisonWithSameDaysOfPreviousWeek() {
        UUID storeId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        StoreKpiPeriod currentPeriod = new StoreKpiPeriod(
                LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 19)
        );
        StoreKpiPeriod previousWeek = new StoreKpiPeriod(
                LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 12)
        );
        EmployeeRatingEntry current = entry(employeeId, 1, "100.00", "1000.00");
        when(ratingService.get(storeId, currentPeriod))
                .thenReturn(result(storeId, currentPeriod, current));
        when(ratingService.get(storeId, previousWeek))
                .thenReturn(result(storeId, previousWeek));

        EmployeeCardView card = service.card(
                storeId, employeeId, currentPeriod,
                EmployeeComparisonMode.PREVIOUS_WEEK
        );

        assertThat(card.previousPeriodStart()).isEqualTo(previousWeek.start());
        assertThat(card.previousPeriodEnd()).isEqualTo(previousWeek.end());
    }

    @Test
    void returnsCardWithoutPayrollWhenFullMonthHasNotBeenCalculated() {
        UUID storeId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        StoreKpiPeriod currentPeriod = new StoreKpiPeriod(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)
        );
        StoreKpiPeriod previousPeriod = new StoreKpiPeriod(
                LocalDate.of(2026, 5, 31), LocalDate.of(2026, 6, 30)
        );
        EmployeeRatingEntry current = entry(employeeId, 1, "110.00", "1200.00");
        when(ratingService.get(storeId, currentPeriod))
                .thenReturn(result(storeId, currentPeriod, current));
        when(ratingService.get(storeId, previousPeriod))
                .thenReturn(result(storeId, previousPeriod));
        when(payrollService.findLatest(storeId, YearMonth.of(2026, 7)))
                .thenReturn(Optional.empty());

        EmployeeCardView card = service.card(storeId, employeeId, currentPeriod);

        assertThat(card.payroll()).isNull();
    }

    private EmployeeRatingResult result(
            UUID storeId,
            StoreKpiPeriod period,
            EmployeeRatingEntry... entries
    ) {
        return new EmployeeRatingResult(
                storeId,
                period.start(),
                period.end(),
                mock(RatingFormulaView.class),
                mock(RatingPlanContext.class),
                List.of(entries), EmployeeRatingHistoryView.live()
        );
    }

    private EmployeeRatingEntry entry(
            UUID employeeId,
            int rank,
            String overallScore,
            String revenue
    ) {
        EmployeeRatingEntry entry = mock(EmployeeRatingEntry.class);
        RatingScoreBreakdown scores = mock(RatingScoreBreakdown.class);
        when(entry.employeeId()).thenReturn(employeeId);
        when(entry.displayName()).thenReturn("Алина");
        when(entry.rank()).thenReturn(rank);
        when(entry.scores()).thenReturn(scores);
        when(scores.overallScore()).thenReturn(new BigDecimal(overallScore));
        when(entry.netRevenue()).thenReturn(new BigDecimal(revenue));
        when(entry.revenuePerHour()).thenReturn(new BigDecimal("100.00"));
        when(entry.accessorySharePercent()).thenReturn(new BigDecimal("4.00"));
        when(entry.serviceSharePercent()).thenReturn(new BigDecimal("3.00"));
        when(entry.additionalSharePercent()).thenReturn(new BigDecimal("7.00"));
        when(entry.attachRates()).thenReturn(List.of());
        return entry;
    }
}
