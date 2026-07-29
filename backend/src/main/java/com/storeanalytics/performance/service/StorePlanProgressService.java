package com.storeanalytics.performance.service;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.common.exception.InvalidRequestException;
import com.storeanalytics.metrics.service.CategoryKpiEntry;
import com.storeanalytics.metrics.service.CategoryKpiResult;
import com.storeanalytics.metrics.service.CategoryKpiService;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.metrics.service.StoreKpiResult;
import com.storeanalytics.metrics.service.StoreKpiService;
import com.storeanalytics.product.model.AnalyticsCategoryKind;
import com.storeanalytics.store.service.StoreDataStatusService;
import com.storeanalytics.store.service.StoreDataStatusView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StorePlanProgressService {

    static final String FORMULA_VERSION = "store-plan-progress-v1";
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final int MONEY_SCALE = 2;
    private static final int PERCENT_SCALE = 2;

    private final StorePerformancePlanService planService;
    private final StoreKpiService storeKpiService;
    private final CategoryKpiService categoryKpiService;
    private final StoreDataStatusService dataStatusService;
    private final Clock clock;

    public StorePlanProgressService(
            StorePerformancePlanService planService,
            StoreKpiService storeKpiService,
            CategoryKpiService categoryKpiService,
            StoreDataStatusService dataStatusService,
            Clock clock
    ) {
        this.planService = planService;
        this.storeKpiService = storeKpiService;
        this.categoryKpiService = categoryKpiService;
        this.dataStatusService = dataStatusService;
        this.clock = clock;
    }


    @Transactional(readOnly = true)
    public java.util.Optional<StorePlanProgressView> find(
            UUID storeId,
            YearMonth month,
            LocalDate asOfDate
    ) {
        if (planService.find(storeId, month).isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(calculate(storeId, month, asOfDate));
    }

    @Transactional(readOnly = true)
    public StorePlanProgressView calculate(
            UUID storeId,
            YearMonth month,
            LocalDate asOfDate
    ) {
        UUID validatedStoreId = requireNonNull(storeId, "storeId");
        YearMonth validatedMonth = requireNonNull(month, "month");
        LocalDate asOf = requireAsOf(validatedMonth, asOfDate);
        LocalDate start = validatedMonth.atDay(1);
        LocalDate end = validatedMonth.atEndOfMonth();
        StoreKpiPeriod period = new StoreKpiPeriod(start, asOf);

        StorePerformancePlanView plan = planService.get(validatedStoreId, validatedMonth);
        StoreKpiResult storeKpi = storeKpiService.calculate(validatedStoreId, period);
        CategoryKpiResult categoryKpi = categoryKpiService.calculate(validatedStoreId, period);
        StoreDataStatusView dataStatus = dataStatusService.get(validatedStoreId);

        BigDecimal revenue = money(storeKpi.netRevenue());
        BigDecimal accessories = categoryAmount(
                categoryKpi,
                entry -> entry.categoryKind() == AnalyticsCategoryKind.ACCESSORY
        );
        BigDecimal services = categoryAmount(
                categoryKpi,
                entry -> entry.categoryKind() == AnalyticsCategoryKind.SERVICE
                        || entry.categoryKind() == AnalyticsCategoryKind.WARRANTY
                        || entry.categoryKind() == AnalyticsCategoryKind.PROTECTION
        );
        BigDecimal additional = categoryAmount(
                categoryKpi,
                CategoryKpiEntry::countsAsAdditionalRevenue
        );

        int totalDays = validatedMonth.lengthOfMonth();
        int elapsedDays = asOf.getDayOfMonth();
        int remainingDays = totalDays - elapsedDays;
        Timeline timeline = new Timeline(elapsedDays, remainingDays, totalDays);
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
                        plan.revenueTarget(),
                        plan.accessoryShareTarget(),
                        timeline
                ),
                shareDirection(
                        StorePlanDirectionCode.SERVICE,
                        services,
                        revenue,
                        plan.revenueTarget(),
                        plan.serviceShareTarget(),
                        timeline
                ),
                shareDirection(
                        StorePlanDirectionCode.ADDITIONAL,
                        additional,
                        revenue,
                        plan.revenueTarget(),
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
                storeKpi.dataQuality().unmappedItemCount() == 0,
                storeKpi.dataQuality().unmappedItemCount(),
                dataStatus.openQualityIssueCount()
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

    private BigDecimal categoryAmount(
            CategoryKpiResult result,
            Predicate<CategoryKpiEntry> membership
    ) {
        return money(result.categories().stream()
                .filter(membership)
                .map(entry -> entry.metrics().netRevenue())
                .reduce(BigDecimal.ZERO, BigDecimal::add));
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
                        achieved,
                        status
                )
        );
    }

    private StorePlanDirectionView shareDirection(
            StorePlanDirectionCode code,
            BigDecimal actual,
            BigDecimal revenue,
            BigDecimal revenueTarget,
            BigDecimal shareTarget,
            Timeline timeline
    ) {
        BigDecimal normalizedShareTarget = percentValue(shareTarget);
        BigDecimal targetAmount = money(
                revenueTarget.multiply(normalizedShareTarget).divide(ONE_HUNDRED)
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
        PaceAmounts pace = pace(actual, targetAmount, timeline);
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
                percent(pace.projectedAmount(), target),
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

    private PaceAmounts pace(
            BigDecimal actual,
            BigDecimal target,
            Timeline timeline
    ) {
        return pace(
                actual,
                target,
                timeline.elapsedDays(),
                timeline.remainingDays(),
                timeline.totalDays()
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
