package com.storeanalytics.performance.service;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.audit.service.AuditAction;
import com.storeanalytics.audit.service.AuditEntityType;
import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.audit.service.AuditTarget;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.metrics.exception.StoreNotFoundException;
import com.storeanalytics.performance.exception.PerformancePlanNotFoundException;
import com.storeanalytics.performance.model.StorePerformancePlan;
import com.storeanalytics.performance.model.StorePlanTargets;
import com.storeanalytics.performance.repository.StorePerformancePlanRepository;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.repository.StoreRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StorePerformancePlanService {

    private static final int PERCENT_SCALE = 2;

    private final StorePerformancePlanRepository planRepository;
    private final StoreRepository storeRepository;
    private final AppUserRepository userRepository;
    private final AuditLogService auditLogService;

    public StorePerformancePlanService(
            StorePerformancePlanRepository planRepository,
            StoreRepository storeRepository,
            AppUserRepository userRepository,
            AuditLogService auditLogService
    ) {
        this.planRepository = planRepository;
        this.storeRepository = storeRepository;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public StorePerformancePlanView get(UUID storeId, YearMonth month) {
        UUID validatedStoreId = requireStore(storeId).getId();
        LocalDate planMonth = requireNonNull(month, "month").atDay(1);
        return planRepository.findByStoreIdAndPlanMonth(validatedStoreId, planMonth)
                .map(this::toView)
                .orElseThrow(() -> new PerformancePlanNotFoundException(
                        validatedStoreId, planMonth
                ));
    }

    @Transactional
    public StorePerformancePlanView upsert(
            UUID storeId,
            YearMonth month,
            StorePlanTargets targets,
            UUID actorId
    ) {
        Store store = requireStore(storeId);
        AppUser actor = userRepository.findById(requireNonNull(actorId, "actorId"))
                .orElseThrow(() -> new IllegalArgumentException("actor does not exist"));
        LocalDate planMonth = requireNonNull(month, "month").atDay(1);
        StorePerformancePlan existing = planRepository
                .findByStoreIdAndPlanMonth(store.getId(), planMonth)
                .orElse(null);
        Map<String, Object> before = existing == null ? null : planSummary(existing);
        StorePerformancePlan plan;
        if (existing == null) {
            plan = new StorePerformancePlan(store, planMonth, targets, actor);
        } else {
            existing.update(targets, actor);
            plan = existing;
        }
        StorePerformancePlan saved = planRepository.saveAndFlush(plan);
        auditLogService.record(
                actorId,
                store.getId(),
                AuditAction.PERFORMANCE_PLAN_CHANGED,
                new AuditTarget(AuditEntityType.PERFORMANCE_PLAN, saved.getId()),
                null,
                before,
                planSummary(saved)
        );
        return toView(saved);
    }

    @Transactional(readOnly = true)
    public RatingPlanContext context(
            UUID storeId,
            LocalDate periodStart,
            LocalDate periodEnd,
            BigDecimal actualStoreRevenue
    ) {
        LocalDate start = requireNonNull(periodStart, "periodStart");
        LocalDate end = requireNonNull(periodEnd, "periodEnd");
        YearMonth firstMonth = YearMonth.from(start);
        YearMonth lastMonth = YearMonth.from(end);
        List<StorePerformancePlan> plans = planRepository
                .findAllByStoreIdAndPlanMonthBetweenOrderByPlanMonth(
                        storeId, firstMonth.atDay(1), lastMonth.atDay(1)
                );
        Map<YearMonth, StorePerformancePlan> byMonth = new HashMap<>();
        plans.forEach(plan -> byMonth.put(YearMonth.from(plan.getPlanMonth()), plan));

        long totalDays = ChronoUnit.DAYS.between(start, end) + 1;
        long coveredDays = 0;
        BigDecimal proratedRevenue = BigDecimal.ZERO;
        BigDecimal accessoryWeighted = BigDecimal.ZERO;
        BigDecimal serviceWeighted = BigDecimal.ZERO;
        BigDecimal additionalWeighted = BigDecimal.ZERO;
        YearMonth cursor = firstMonth;
        while (!cursor.isAfter(lastMonth)) {
            LocalDate overlapStart = start.isAfter(cursor.atDay(1)) ? start : cursor.atDay(1);
            LocalDate overlapEnd = end.isBefore(cursor.atEndOfMonth())
                    ? end : cursor.atEndOfMonth();
            long days = ChronoUnit.DAYS.between(overlapStart, overlapEnd) + 1;
            StorePerformancePlan plan = byMonth.get(cursor);
            if (plan != null) {
                coveredDays += days;
                BigDecimal dayRatio = BigDecimal.valueOf(days)
                        .divide(BigDecimal.valueOf(cursor.lengthOfMonth()), 10, RoundingMode.HALF_UP);
                proratedRevenue = proratedRevenue.add(plan.getRevenueTarget().multiply(dayRatio));
                BigDecimal dayWeight = BigDecimal.valueOf(days);
                accessoryWeighted = accessoryWeighted.add(
                        plan.getAccessoryShareTarget().multiply(dayWeight)
                );
                serviceWeighted = serviceWeighted.add(
                        plan.getServiceShareTarget().multiply(dayWeight)
                );
                additionalWeighted = additionalWeighted.add(
                        plan.getAdditionalShareTarget().multiply(dayWeight)
                );
            }
            cursor = cursor.plusMonths(1);
        }

        BigDecimal coverage = percent(BigDecimal.valueOf(coveredDays), BigDecimal.valueOf(totalDays));
        if (coveredDays == 0) {
            return new RatingPlanContext(
                    false, coverage, null, null, null, null,
                    money(actualStoreRevenue), null
            );
        }
        BigDecimal coveredDayCount = BigDecimal.valueOf(coveredDays);
        BigDecimal revenueTarget = proratedRevenue.setScale(2, RoundingMode.HALF_UP);
        BigDecimal actualRevenue = money(actualStoreRevenue);
        return new RatingPlanContext(
                coveredDays == totalDays,
                coverage,
                revenueTarget,
                weightedAverage(accessoryWeighted, coveredDayCount),
                weightedAverage(serviceWeighted, coveredDayCount),
                weightedAverage(additionalWeighted, coveredDayCount),
                actualRevenue,
                revenueTarget.signum() <= 0 ? null : percent(actualRevenue, revenueTarget)
        );
    }

    private Store requireStore(UUID storeId) {
        UUID validated = requireNonNull(storeId, "storeId");
        return storeRepository.findById(validated)
                .orElseThrow(() -> new StoreNotFoundException(validated));
    }

    private StorePerformancePlanView toView(StorePerformancePlan plan) {
        return new StorePerformancePlanView(
                plan.getId(),
                plan.getStore().getId(),
                plan.getPlanMonth(),
                plan.getRevenueTarget(),
                plan.getAccessoryShareTarget(),
                plan.getServiceShareTarget(),
                plan.getAdditionalShareTarget(),
                plan.getUpdatedBy() == null ? null : plan.getUpdatedBy().getId(),
                plan.getVersion(),
                plan.getUpdatedAt()
        );
    }

    private Map<String, Object> planSummary(StorePerformancePlan plan) {
        return Map.of(
                "month", plan.getPlanMonth(),
                "revenueTarget", plan.getRevenueTarget(),
                "accessoryShareTarget", plan.getAccessoryShareTarget(),
                "serviceShareTarget", plan.getServiceShareTarget(),
                "additionalShareTarget", plan.getAdditionalShareTarget(),
                "version", plan.getVersion()
        );
    }

    private BigDecimal weightedAverage(BigDecimal weighted, BigDecimal days) {
        return weighted.divide(days, PERCENT_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
        return numerator.multiply(BigDecimal.valueOf(100))
                .divide(denominator, PERCENT_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal money(BigDecimal value) {
        return requireNonNull(value, "actualStoreRevenue").setScale(2, RoundingMode.UNNECESSARY);
    }
}
