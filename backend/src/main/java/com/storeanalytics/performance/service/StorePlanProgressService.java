package com.storeanalytics.performance.service;

import static com.storeanalytics.common.time.ReportingCutoffPolicy.clampToCompletedDay;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.common.exception.InvalidRequestException;
import com.storeanalytics.metrics.service.OverviewMetricScope;
import com.storeanalytics.metrics.service.OverviewMetricsResult;
import com.storeanalytics.metrics.service.OverviewMetricsService;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.performance.repository.StorePlanDailyActual;
import com.storeanalytics.performance.repository.StorePlanDailyActualRepository;
import com.storeanalytics.store.service.StoreDataStatusService;
import com.storeanalytics.store.service.StoreDataStatusView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StorePlanProgressService {

    static final String FORMULA_VERSION = "store-plan-progress-v3";
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final int MONEY_SCALE = 2;
    private static final int PERCENT_SCALE = 2;
    private static final int CALCULATION_SCALE = 8;

    private final StorePerformancePlanService planService;
    private final OverviewMetricsService overviewMetricsService;
    private final StorePlanDailyActualRepository dailyActualRepository;
    private final StoreDataStatusService dataStatusService;
    private final Clock clock;

    public StorePlanProgressService(
            StorePerformancePlanService planService,
            OverviewMetricsService overviewMetricsService,
            StorePlanDailyActualRepository dailyActualRepository,
            StoreDataStatusService dataStatusService,
            Clock clock
    ) {
        this.planService = planService;
        this.overviewMetricsService = overviewMetricsService;
        this.dailyActualRepository = dailyActualRepository;
        this.dataStatusService = dataStatusService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public java.util.Optional<StorePlanProgressView> find(
            UUID storeId,
            YearMonth month,
            LocalDate asOfDate
    ) {
        return find(storeId, month, asOfDate, OverviewMetricScope.STORE);
    }

    @Transactional(readOnly = true)
    public java.util.Optional<StorePlanProgressView> find(
            UUID storeId,
            YearMonth month,
            LocalDate asOfDate,
            OverviewMetricScope scope
    ) {
        if (planService.find(storeId, month).isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(calculate(storeId, month, asOfDate, scope));
    }

    @Transactional(readOnly = true)
    public StorePlanProgressView calculate(
            UUID storeId,
            YearMonth month,
            LocalDate asOfDate
    ) {
        return calculate(storeId, month, asOfDate, OverviewMetricScope.STORE);
    }

    @Transactional(readOnly = true)
    public StorePlanProgressView calculate(
            UUID storeId,
            YearMonth month,
            LocalDate asOfDate,
            OverviewMetricScope scope
    ) {
        UUID validatedStoreId = requireNonNull(storeId, "storeId");
        YearMonth validatedMonth = requireNonNull(month, "month");
        OverviewMetricScope validatedScope = requireNonNull(scope, "scope");
        LocalDate requestedAsOf = requireAsOf(validatedMonth, asOfDate);
        StoreDataStatusView dataStatus = dataStatusService.get(validatedStoreId);
        LocalDate asOf = clampToCompletedDay(
                validatedMonth,
                requestedAsOf,
                dataStatus.expectedThroughDate()
        );
        LocalDate start = validatedMonth.atDay(1);
        LocalDate end = validatedMonth.atEndOfMonth();
        StoreKpiPeriod period = new StoreKpiPeriod(start, asOf);

        StorePerformancePlanView plan = planService.get(validatedStoreId, validatedMonth);
        OverviewMetricsResult metrics = overviewMetricsService.calculate(
                validatedStoreId,
                period,
                validatedScope
        );
        List<StorePlanDailyActual> dailyActuals =
                dailyActualRepository.aggregate(
                        validatedStoreId,
                        start,
                        asOf,
                        validatedScope
                );

        BigDecimal revenue = money(metrics.netRevenue());
        BigDecimal accessories = money(metrics.accessory().netRevenue());
        BigDecimal services = money(metrics.service().netRevenue());
        BigDecimal additional = money(metrics.additional().netRevenue());

        int totalDays = validatedMonth.lengthOfMonth();
        int elapsedDays = asOf.getDayOfMonth();
        int remainingDays = totalDays - elapsedDays;
        Timeline timeline = new Timeline(elapsedDays, remainingDays, totalDays);
        List<StorePlanDailyTargetView> dailyTargets = dailyTargets(
                validatedMonth,
                asOf,
                plan,
                dailyActuals,
                revenue,
                accessories,
                services
        );
        List<StorePlanDirectionView> directions = List.of(
                amountDirection(
                        StorePlanDirectionCode.REVENUE,
                        revenue,
                        plan.revenueTarget(),
                        elapsedDays,
                        remainingDays,
                        totalDays
                ),
                shareDirection(
                        StorePlanDirectionCode.ACCESSORY,
                        accessories,
                        revenue,
                        plan.accessoryShareTarget(),
                        timeline
                ),
                shareDirection(
                        StorePlanDirectionCode.SERVICE,
                        services,
                        revenue,
                        plan.serviceShareTarget(),
                        timeline
                ),
                shareDirection(
                        StorePlanDirectionCode.ADDITIONAL,
                        additional,
                        revenue,
                        plan.additionalShareTarget(),
                        timeline
                )
        );
        List<StorePlanDirectionCode> focus = directions.stream()
                .filter(direction -> direction.status() == StorePlanProgressStatus.AT_RISK
                        || direction.status() == StorePlanProgressStatus.MISSED
                        || direction.status() == StorePlanProgressStatus.NOT_AVAILABLE)
                .map(StorePlanDirectionView::code)
                .toList();
        int achievedCount = (int) directions.stream()
                .filter(StorePlanDirectionView::achieved)
                .count();
        StorePlanProgressDataQuality quality = new StorePlanProgressDataQuality(
                dataStatus.status(),
                dataStatus.dataThroughDate(),
                dataStatus.dataThroughDate() != null
                        && !dataStatus.dataThroughDate().isBefore(asOf),
                metrics.dataQuality().unmappedItemCount() == 0,
                metrics.dataQuality().unmappedItemCount(),
                metrics.dataQuality().periodOpenConsistencyIssueCount()
        );
        return new StorePlanProgressView(
                validatedStoreId,
                start,
                end,
                asOf,
                totalDays,
                elapsedDays,
                remainingDays,
                FORMULA_VERSION,
                plan,
                quality,
                achievedCount,
                achievedCount == directions.size(),
                focus,
                directions,
                dailyTargets,
                clock.instant()
        );
    }

    private LocalDate requireAsOf(YearMonth month, LocalDate asOfDate) {
        LocalDate validated = requireNonNull(asOfDate, "asOf");
        if (!YearMonth.from(validated).equals(month)) {
            throw new InvalidRequestException("asOf must be inside the requested month");
        }
        return validated;
    }

    private List<StorePlanDailyTargetView> dailyTargets(
            YearMonth month,
            LocalDate asOf,
            StorePerformancePlanView plan,
            List<StorePlanDailyActual> dailyActuals,
            BigDecimal revenue,
            BigDecimal accessories,
            BigDecimal services
    ) {
        Map<LocalDate, StorePlanDailyActual> actualsByDate = new HashMap<>();
        dailyActuals.forEach(actual -> actualsByDate.put(actual.businessDate(), actual));

        int remainingDays = month.lengthOfMonth() - asOf.getDayOfMonth();
        BigDecimal futureRevenue = futureRevenueBasis(
                revenue,
                asOf.getDayOfMonth(),
                plan.revenueTarget(),
                month.lengthOfMonth()
        );
        BigDecimal projectedRevenue = money(
                revenue.add(futureRevenue.multiply(BigDecimal.valueOf(remainingDays)))
        );
        BigDecimal accessoryRequired = futureRequiredAmount(
                projectedRevenue,
                plan.accessoryShareTarget(),
                accessories
        );
        BigDecimal serviceRequired = futureRequiredAmount(
                projectedRevenue,
                plan.serviceShareTarget(),
                services
        );

        List<StorePlanDailyTargetView> result = new ArrayList<>(month.lengthOfMonth());
        BigDecimal cumulativeRevenue = BigDecimal.ZERO;
        BigDecimal cumulativeAccessories = BigDecimal.ZERO;
        BigDecimal cumulativeServices = BigDecimal.ZERO;
        int futureIndex = 0;
        for (int day = 1; day <= month.lengthOfMonth(); day++) {
            LocalDate date = month.atDay(day);
            if (!date.isAfter(asOf)) {
                StorePlanDailyActual actual = actualsByDate.getOrDefault(
                        date,
                        emptyDailyActual(date)
                );
                cumulativeRevenue = cumulativeRevenue.add(actual.revenueAmount());
                cumulativeAccessories =
                        cumulativeAccessories.add(actual.accessoryAmount());
                cumulativeServices = cumulativeServices.add(actual.serviceAmount());
                result.add(new StorePlanDailyTargetView(
                        date,
                        true,
                        money(actual.revenueAmount()),
                        false,
                        completedDailyDirection(
                                actual.accessoryAmount(),
                                actual.revenueAmount(),
                                plan.accessoryShareTarget(),
                                cumulativeAccessories,
                                cumulativeRevenue
                        ),
                        completedDailyDirection(
                                actual.serviceAmount(),
                                actual.revenueAmount(),
                                plan.serviceShareTarget(),
                                cumulativeServices,
                                cumulativeRevenue
                        )
                ));
            } else {
                futureIndex++;
                result.add(new StorePlanDailyTargetView(
                        date,
                        false,
                        futureRevenue,
                        true,
                        futureDailyDirection(
                                accessoryRequired,
                                remainingDays,
                                futureIndex,
                                futureRevenue
                        ),
                        futureDailyDirection(
                                serviceRequired,
                                remainingDays,
                                futureIndex,
                                futureRevenue
                        )
                ));
            }
        }
        return List.copyOf(result);
    }

    private StorePlanDailyActual emptyDailyActual(LocalDate date) {
        return new StorePlanDailyActual(
                date,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO
        );
    }

    private BigDecimal futureRevenueBasis(
            BigDecimal actualRevenue,
            int elapsedDays,
            BigDecimal revenueTarget,
            int totalDays
    ) {
        BigDecimal basis = actualRevenue.signum() > 0
                ? actualRevenue.divide(
                        BigDecimal.valueOf(elapsedDays),
                        CALCULATION_SCALE,
                        RoundingMode.HALF_UP
                )
                : revenueTarget.divide(
                        BigDecimal.valueOf(totalDays),
                        CALCULATION_SCALE,
                        RoundingMode.HALF_UP
                );
        return money(basis);
    }

    private BigDecimal futureRequiredAmount(
            BigDecimal projectedRevenue,
            BigDecimal targetShare,
            BigDecimal actualAmount
    ) {
        BigDecimal projectedTarget = projectedRevenue
                .multiply(percentValue(targetShare))
                .divide(ONE_HUNDRED, CALCULATION_SCALE, RoundingMode.HALF_UP);
        return money(projectedTarget.subtract(actualAmount).max(BigDecimal.ZERO));
    }

    private StorePlanDailyDirectionView completedDailyDirection(
            BigDecimal actualAmount,
            BigDecimal dailyRevenue,
            BigDecimal targetShare,
            BigDecimal cumulativeAmount,
            BigDecimal cumulativeRevenue
    ) {
        BigDecimal normalizedTargetShare = percentValue(targetShare);
        BigDecimal targetAmount = money(
                dailyRevenue.multiply(normalizedTargetShare)
                        .divide(ONE_HUNDRED, CALCULATION_SCALE, RoundingMode.HALF_UP)
        );
        BigDecimal cumulativeTarget = cumulativeRevenue
                .multiply(normalizedTargetShare)
                .divide(ONE_HUNDRED, CALCULATION_SCALE, RoundingMode.HALF_UP);
        return new StorePlanDailyDirectionView(
                money(actualAmount),
                dailyRevenue.signum() > 0 ? percent(actualAmount, dailyRevenue) : null,
                targetAmount,
                normalizedTargetShare,
                money(cumulativeAmount.subtract(cumulativeTarget))
        );
    }

    private StorePlanDailyDirectionView futureDailyDirection(
            BigDecimal requiredAmount,
            int remainingDays,
            int futureIndex,
            BigDecimal revenueBasis
    ) {
        BigDecimal targetAmount = allocatedAmount(
                requiredAmount,
                remainingDays,
                futureIndex
        );
        return new StorePlanDailyDirectionView(
                null,
                null,
                targetAmount,
                percent(targetAmount, revenueBasis),
                null
        );
    }

    private BigDecimal allocatedAmount(
            BigDecimal total,
            int count,
            int index
    ) {
        BigDecimal base = total.divide(
                BigDecimal.valueOf(count),
                MONEY_SCALE,
                RoundingMode.DOWN
        );
        if (index < count) {
            return money(base);
        }
        return money(total.subtract(base.multiply(BigDecimal.valueOf(count - 1L))));
    }


    private StorePlanDirectionView amountDirection(
            StorePlanDirectionCode code,
            BigDecimal actual,
            BigDecimal target,
            int elapsedDays,
            int remainingDays,
            int totalDays
    ) {
        BigDecimal normalizedTarget = money(target);
        boolean achieved = actual.compareTo(normalizedTarget) >= 0;
        PaceAmounts pace = pace(actual, normalizedTarget, elapsedDays, remainingDays, totalDays);
        StorePlanProgressStatus status = status(
                achieved,
                true,
                remainingDays,
                pace.projectedAmount().compareTo(normalizedTarget) >= 0
        );
        BigDecimal completion = percent(actual, normalizedTarget);
        return direction(
                code,
                StorePlanCriterionType.AMOUNT,
                actual,
                normalizedTarget,
                completion,
                pace,
                new CriterionDetails(
                        null,
                        null,
                        null,
                        completion,
                        percent(pace.projectedAmount(), normalizedTarget),
                        achieved,
                        status
                )
        );
    }

    private StorePlanDirectionView shareDirection(
            StorePlanDirectionCode code,
            BigDecimal actual,
            BigDecimal revenue,
            BigDecimal shareTarget,
            Timeline timeline
    ) {
        BigDecimal normalizedShareTarget = percentValue(shareTarget);
        BigDecimal targetAmount = money(
                revenue.multiply(normalizedShareTarget).divide(ONE_HUNDRED)
        );
        boolean shareAvailable = revenue.signum() > 0;
        BigDecimal actualShare = shareAvailable ? percent(actual, revenue) : null;
        boolean achieved = shareAvailable && actual.multiply(ONE_HUNDRED)
                .compareTo(normalizedShareTarget.multiply(revenue)) >= 0;
        BigDecimal criterionCompletion = shareAvailable && normalizedShareTarget.signum() > 0
                ? actual.multiply(ONE_HUNDRED).multiply(ONE_HUNDRED)
                        .divide(
                                normalizedShareTarget.multiply(revenue),
                                PERCENT_SCALE,
                                RoundingMode.HALF_UP
                        )
                : null;
        PaceAmounts pace = sharePace(actual, targetAmount, timeline);
        StorePlanProgressStatus status = status(
                achieved,
                shareAvailable,
                timeline.remainingDays(),
                false
        );
        return direction(
                code,
                StorePlanCriterionType.SHARE,
                actual,
                targetAmount,
                percent(actual, targetAmount),
                pace,
                new CriterionDetails(
                        actualShare,
                        normalizedShareTarget,
                        actualShare == null
                                ? null : percentValue(
                                        actualShare.subtract(normalizedShareTarget)
                                ),
                        criterionCompletion,
                        criterionCompletion,
                        achieved,
                        status
                )
        );
    }

    private StorePlanDirectionView direction(
            StorePlanDirectionCode code,
            StorePlanCriterionType criterionType,
            BigDecimal actual,
            BigDecimal target,
            BigDecimal amountCompletion,
            PaceAmounts pace,
            CriterionDetails criterion
    ) {
        return new StorePlanDirectionView(
                code,
                criterionType,
                actual,
                target,
                amountCompletion,
                pace.currentDailyPace(),
                pace.expectedAmountToDate(),
                pace.paceGapAmount(),
                pace.projectedAmount(),
                criterion.projectedCompletion(),
                pace.remainingAmount(),
                pace.requiredPerRemainingDay(),
                criterion.actualShare(),
                criterion.targetShare(),
                criterion.shareGap(),
                criterion.completion(),
                criterion.achieved(),
                criterion.status()
        );
    }

    private PaceAmounts sharePace(
            BigDecimal actual,
            BigDecimal targetToDate,
            Timeline timeline
    ) {
        BigDecimal currentPace = money(actual.divide(
                BigDecimal.valueOf(timeline.elapsedDays()),
                MONEY_SCALE,
                RoundingMode.HALF_UP
        ));
        BigDecimal projected = money(actual.multiply(BigDecimal.valueOf(timeline.totalDays()))
                .divide(
                        BigDecimal.valueOf(timeline.elapsedDays()),
                        MONEY_SCALE,
                        RoundingMode.HALF_UP
                ));
        BigDecimal remaining = money(targetToDate.subtract(actual).max(BigDecimal.ZERO));
        BigDecimal required = remaining.signum() == 0
                ? money(BigDecimal.ZERO)
                : timeline.remainingDays() == 0
                        ? null
                        : money(remaining.divide(
                                BigDecimal.valueOf(timeline.remainingDays()),
                                MONEY_SCALE,
                                RoundingMode.HALF_UP
                        ));
        return new PaceAmounts(
                currentPace,
                targetToDate,
                money(actual.subtract(targetToDate)),
                projected,
                remaining,
                required
        );
    }


    private PaceAmounts pace(
            BigDecimal actual,
            BigDecimal target,
            int elapsedDays,
            int remainingDays,
            int totalDays
    ) {
        BigDecimal currentPace = money(actual.divide(
                BigDecimal.valueOf(elapsedDays),
                MONEY_SCALE,
                RoundingMode.HALF_UP
        ));
        BigDecimal expected = money(target.multiply(BigDecimal.valueOf(elapsedDays))
                .divide(BigDecimal.valueOf(totalDays), MONEY_SCALE, RoundingMode.HALF_UP));
        BigDecimal projected = money(actual.multiply(BigDecimal.valueOf(totalDays))
                .divide(
                        BigDecimal.valueOf(elapsedDays),
                        MONEY_SCALE,
                        RoundingMode.HALF_UP
                ));
        BigDecimal remaining = money(target.subtract(actual).max(BigDecimal.ZERO));
        BigDecimal required = remaining.signum() == 0
                ? money(BigDecimal.ZERO)
                : remainingDays == 0
                        ? null
                        : money(remaining.divide(
                                BigDecimal.valueOf(remainingDays),
                                MONEY_SCALE,
                                RoundingMode.HALF_UP
                        ));
        return new PaceAmounts(
                currentPace,
                expected,
                money(actual.subtract(expected)),
                projected,
                remaining,
                required
        );
    }

    private StorePlanProgressStatus status(
            boolean achieved,
            boolean criterionAvailable,
            int remainingDays,
            boolean projectedAchievement
    ) {
        if (achieved) {
            return StorePlanProgressStatus.ACHIEVED;
        }
        if (remainingDays == 0) {
            return StorePlanProgressStatus.MISSED;
        }
        if (!criterionAvailable) {
            return StorePlanProgressStatus.NOT_AVAILABLE;
        }
        return projectedAchievement
                ? StorePlanProgressStatus.ON_TRACK
                : StorePlanProgressStatus.AT_RISK;
    }

    private BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
        return denominator.signum() == 0
                ? null
                : numerator.multiply(ONE_HUNDRED)
                        .divide(denominator, PERCENT_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal money(BigDecimal value) {
        return requireNonNull(value, "money").setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private record Timeline(int elapsedDays, int remainingDays, int totalDays) {
    }

    private record CriterionDetails(
            BigDecimal actualShare,
            BigDecimal targetShare,
            BigDecimal shareGap,
            BigDecimal completion,
            BigDecimal projectedCompletion,
            boolean achieved,
            StorePlanProgressStatus status
    ) {
    }

    private BigDecimal percentValue(BigDecimal value) {
        return requireNonNull(value, "percent").setScale(PERCENT_SCALE, RoundingMode.HALF_UP);
    }

    private record PaceAmounts(
            BigDecimal currentDailyPace,
            BigDecimal expectedAmountToDate,
            BigDecimal paceGapAmount,
            BigDecimal projectedAmount,
            BigDecimal remainingAmount,
            BigDecimal requiredPerRemainingDay
    ) {
    }
}
