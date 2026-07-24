package com.storeanalytics.performance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.performance.model.StorePerformancePlan;
import com.storeanalytics.performance.repository.StorePerformancePlanRepository;
import com.storeanalytics.store.repository.StoreRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StorePerformancePlanServiceTest {

    private StorePerformancePlanRepository planRepository;
    private StorePerformancePlanService service;

    @BeforeEach
    void setUp() {
        planRepository = mock(StorePerformancePlanRepository.class);
        service = new StorePerformancePlanService(
                planRepository,
                mock(StoreRepository.class),
                mock(AppUserRepository.class),
                mock(com.storeanalytics.audit.service.AuditLogService.class)
        );
    }

    @Test
    void proratesRevenueAndReportsCompleteCoverageForOneMonth() {
        UUID storeId = UUID.randomUUID();
        StorePerformancePlan julyPlan = plan(
                LocalDate.of(2026, 7, 1), "3100000.00", "4.00", "3.00", "7.00"
        );
        when(planRepository.findAllByStoreIdAndPlanMonthBetweenOrderByPlanMonth(
                storeId, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 1)
        )).thenReturn(List.of(julyPlan));

        RatingPlanContext result = service.context(
                storeId,
                LocalDate.of(2026, 7, 16),
                LocalDate.of(2026, 7, 31),
                new BigDecimal("1200000.00")
        );

        assertThat(result.complete()).isTrue();
        assertThat(result.coveragePercent()).isEqualByComparingTo("100.00");
        assertThat(result.proratedRevenueTarget()).isEqualByComparingTo("1600000.00");
        assertThat(result.accessoryShareTarget()).isEqualByComparingTo("4.00");
        assertThat(result.revenueAchievementPercent()).isEqualByComparingTo("75.00");
    }

    @Test
    void marksCrossMonthContextIncompleteWhenOneMonthlyPlanIsMissing() {
        UUID storeId = UUID.randomUUID();
        StorePerformancePlan julyPlan = plan(
                LocalDate.of(2026, 7, 1), "3100000.00", "4.00", "3.00", "7.00"
        );
        when(planRepository.findAllByStoreIdAndPlanMonthBetweenOrderByPlanMonth(
                storeId, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1)
        )).thenReturn(List.of(julyPlan));

        RatingPlanContext result = service.context(
                storeId,
                LocalDate.of(2026, 7, 16),
                LocalDate.of(2026, 8, 15),
                new BigDecimal("900000.00")
        );

        assertThat(result.complete()).isFalse();
        assertThat(result.coveragePercent()).isEqualByComparingTo("51.61");
        assertThat(result.proratedRevenueTarget()).isEqualByComparingTo("1600000.00");
        assertThat(result.additionalShareTarget()).isEqualByComparingTo("7.00");
        assertThat(result.revenueAchievementPercent()).isEqualByComparingTo("56.25");
    }

    private StorePerformancePlan plan(
            LocalDate month,
            String revenue,
            String accessoryShare,
            String serviceShare,
            String additionalShare
    ) {
        StorePerformancePlan plan = mock(StorePerformancePlan.class);
        when(plan.getPlanMonth()).thenReturn(month);
        when(plan.getRevenueTarget()).thenReturn(new BigDecimal(revenue));
        when(plan.getAccessoryShareTarget()).thenReturn(new BigDecimal(accessoryShare));
        when(plan.getServiceShareTarget()).thenReturn(new BigDecimal(serviceShare));
        when(plan.getAdditionalShareTarget()).thenReturn(new BigDecimal(additionalShare));
        return plan;
    }
}
