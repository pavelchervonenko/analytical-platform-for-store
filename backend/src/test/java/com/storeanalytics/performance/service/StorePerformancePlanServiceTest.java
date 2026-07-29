package com.storeanalytics.performance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.common.exception.PreconditionFailedException;
import com.storeanalytics.common.exception.PreconditionRequiredException;
import com.storeanalytics.performance.model.StorePerformancePlan;
import com.storeanalytics.performance.model.StorePlanTargets;
import com.storeanalytics.performance.repository.StorePerformancePlanRepository;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.repository.StoreRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StorePerformancePlanServiceTest {

    private StorePerformancePlanRepository planRepository;
    private StoreRepository storeRepository;
    private AppUserRepository userRepository;
    private StorePerformancePlanService service;

    @BeforeEach
    void setUp() {
        planRepository = mock(StorePerformancePlanRepository.class);
        storeRepository = mock(StoreRepository.class);
        userRepository = mock(AppUserRepository.class);
        service = new StorePerformancePlanService(
                planRepository,
                storeRepository,
                userRepository,
                mock(com.storeanalytics.audit.service.AuditLogService.class)
        );
    }

    @Test
    void rejectsStalePlanEtagBeforeChangingEntity() {
        UUID storeId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        LocalDate month = LocalDate.of(2026, 7, 1);
        Store store = mock(Store.class);
        AppUser actor = mock(AppUser.class);
        StorePerformancePlan existing = mock(StorePerformancePlan.class);
        when(store.getId()).thenReturn(storeId);
        when(storeRepository.findByIdForUpdate(storeId)).thenReturn(Optional.of(store));
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        when(planRepository.findByStoreIdAndPlanMonth(storeId, month))
                .thenReturn(Optional.of(existing));
        when(existing.getId()).thenReturn(planId);
        when(existing.getVersion()).thenReturn(3L);

        assertThatThrownBy(() -> service.upsert(
                storeId,
                YearMonth.of(2026, 7),
                targets(),
                StorePerformancePlanService.etag(view(planId, storeId, 2)),
                null,
                actorId
        )).isInstanceOf(PreconditionFailedException.class);

        verify(existing, never()).update(targets(), actor);
    }

    @Test
    void requiresIfNoneMatchWhenCreatingPlan() {
        UUID storeId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Store store = mock(Store.class);
        when(store.getId()).thenReturn(storeId);
        when(storeRepository.findByIdForUpdate(storeId)).thenReturn(Optional.of(store));
        when(userRepository.findById(actorId)).thenReturn(Optional.of(mock(AppUser.class)));
        when(planRepository.findByStoreIdAndPlanMonth(
                storeId, LocalDate.of(2026, 7, 1)
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upsert(
                storeId,
                YearMonth.of(2026, 7),
                targets(),
                null,
                null,
                actorId
        )).isInstanceOf(PreconditionRequiredException.class);
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

    private StorePlanTargets targets() {
        return new StorePlanTargets(
                new BigDecimal("1000000.00"),
                new BigDecimal("4.00"),
                new BigDecimal("3.00"),
                new BigDecimal("7.00")
        );
    }

    private StorePerformancePlanView view(UUID id, UUID storeId, long version) {
        return new StorePerformancePlanView(
                id,
                storeId,
                LocalDate.of(2026, 7, 1),
                new BigDecimal("1000000.00"),
                new BigDecimal("4.00"),
                new BigDecimal("3.00"),
                new BigDecimal("7.00"),
                null,
                version,
                null
        );
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
