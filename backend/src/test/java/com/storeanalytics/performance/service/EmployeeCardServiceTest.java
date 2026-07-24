package com.storeanalytics.performance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.salary.service.PayrollManagementService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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

    private EmployeeRatingResult result(
            UUID storeId,
            StoreKpiPeriod period,
            EmployeeRatingEntry entry
    ) {
        return new EmployeeRatingResult(
                storeId,
                period.start(),
                period.end(),
                mock(RatingFormulaView.class),
                mock(RatingPlanContext.class),
                List.of(entry), EmployeeRatingHistoryView.live()
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
