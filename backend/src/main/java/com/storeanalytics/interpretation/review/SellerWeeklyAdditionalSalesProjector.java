package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.interpretation.review.WeeklyReviewPolicyV1.DeltaMode.ABSOLUTE;
import static com.storeanalytics.interpretation.review.WeeklyReviewPolicyV1.DeltaMode.RELATIVE;
import static com.storeanalytics.interpretation.review.WeeklyReviewPolicyV1.Polarity.HIGHER_IS_BETTER;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricState.LIMITED;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricState.READY;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricState.UNAVAILABLE;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.Sufficiency.INSUFFICIENT;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.Sufficiency.SUFFICIENT;

import com.storeanalytics.interpretation.review.WeeklyReviewPolicyV1.MetricSpec;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricComparison;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Sufficiency;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Unit;
import com.storeanalytics.metrics.service.CategoryKpiGroup;
import com.storeanalytics.metrics.service.SellerPeriodFacts;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Component;

/** Compact seller additional-sales summary; detailed category/attach structure remains separate. */
@Component
final class SellerWeeklyAdditionalSalesProjector {

    private final WeeklyReviewPolicyV1 policy = new WeeklyReviewPolicyV1();

    Projection project(
            SellerPeriodFacts current,
            SellerPeriodFacts previous,
            boolean sourceBlocked,
            boolean categoryQualityComplete
    ) {
        SellerPeriodFacts selected = requireNonNull(current, "current");
        SellerPeriodFacts baseline = requireNonNull(previous, "previous");
        MetricState state = sourceBlocked ? UNAVAILABLE : categoryQualityComplete ? READY : LIMITED;
        Sufficiency sufficiency = state == READY ? SUFFICIENT
                : state == LIMITED ? Sufficiency.LIMITED : INSUFFICIENT;
        if (sourceBlocked) {
            MetricComparison unavailableRevenue = policy.compare(
                    spec("ADDITIONAL_REVENUE", "Дополнительная выручка", Unit.RUB,
                            RELATIVE, policy.storeRelativeThreshold()),
                    null, null, UNAVAILABLE, INSUFFICIENT, null, null);
            MetricComparison unavailableShare = policy.compare(
                    spec("ADDITIONAL_SHARE", "Доля допов в выручке продавцов", Unit.PERCENT,
                            ABSOLUTE, policy.shareThreshold()),
                    null, null, UNAVAILABLE, INSUFFICIENT, null, null);
            return new Projection(unavailableRevenue, unavailableShare,
                    null, null, null, null, null, false);
        }
        BigDecimal currentAdditional = amount(selected, "ADDITIONAL_REVENUE");
        BigDecimal previousAdditional = amount(baseline, "ADDITIONAL_REVENUE");
        BigDecimal currentAccessory = amount(selected, "ACCESSORY");
        BigDecimal currentService = amount(selected, "SERVICE");
        require(currentAccessory.add(currentService).compareTo(currentAdditional) == 0,
                "seller additional amount does not match accessories plus services");
        BigDecimal previousAccessory = amount(baseline, "ACCESSORY");
        BigDecimal previousService = amount(baseline, "SERVICE");
        require(previousAccessory.add(previousService).compareTo(previousAdditional) == 0,
                "previous seller additional amount does not reconcile");
        BigDecimal sellerRevenue = selected.metrics().totals().netRevenue();
        BigDecimal previousRevenue = baseline.metrics().totals().netRevenue();
        BigDecimal currentShare = share(currentAdditional, sellerRevenue);
        BigDecimal previousShare = share(previousAdditional, previousRevenue);
        MetricComparison additional = policy.compare(
                spec("ADDITIONAL_REVENUE", "Дополнительная выручка", Unit.RUB,
                        RELATIVE, policy.storeRelativeThreshold()),
                sourceBlocked ? null : currentAdditional,
                sourceBlocked ? null : previousAdditional,
                state, sufficiency, null, null);
        MetricComparison share = policy.compare(
                spec("ADDITIONAL_SHARE", "Доля допов в выручке продавцов", Unit.PERCENT,
                        ABSOLUTE, policy.shareThreshold()),
                sourceBlocked ? null : currentShare,
                sourceBlocked ? null : previousShare,
                currentShare == null ? UNAVAILABLE : state,
                currentShare == null ? INSUFFICIENT : sufficiency,
                null, null);
        return new Projection(additional, share, currentAccessory, currentService,
                share(currentAccessory, currentAdditional),
                share(currentService, currentAdditional),
                currentAdditional.subtract(currentAccessory).subtract(currentService),
                categoryQualityComplete && sellerRevenue.signum() > 0
                        && currentAdditional.signum() > 0
                        && currentAccessory.signum() >= 0 && currentService.signum() >= 0);
    }

    private BigDecimal amount(SellerPeriodFacts period, String code) {
        List<CategoryKpiGroup> groups = period.metrics().categories().groups();
        return groups.stream().filter(group -> code.equals(group.groupCode()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing seller category group " + code))
                .metrics().netRevenue();
    }

    private BigDecimal share(BigDecimal part, BigDecimal denominator) {
        return denominator.signum() <= 0 ? null : part.multiply(BigDecimal.valueOf(100))
                .divide(denominator, 2, RoundingMode.HALF_UP);
    }

    private MetricSpec spec(
            String code,
            String label,
            Unit unit,
            WeeklyReviewPolicyV1.DeltaMode deltaMode,
            BigDecimal threshold
    ) {
        return new MetricSpec("sellers:" + code.toLowerCase(java.util.Locale.ROOT),
                code, label, unit, HIGHER_IS_BETTER, deltaMode, threshold, "SELLERS." + code);
    }

    record Projection(
            MetricComparison additionalRevenue,
            MetricComparison additionalShare,
            BigDecimal accessoryRevenue,
            BigDecimal serviceRevenue,
            BigDecimal accessoryMixShare,
            BigDecimal serviceMixShare,
            BigDecimal integrityResidual,
            boolean compositionChartSafe
    ) {
    }
}
