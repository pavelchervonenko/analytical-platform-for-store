package com.storeanalytics.interpretation.snapshot;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Versions;
import java.math.BigDecimal;

/**
 * Candidate policy v1. Thresholds are backend-owned and versioned so that a prompt change cannot
 * silently redefine material movement, a plan gap, or a comparable employee sample.
 */
public final class WeeklySnapshotPolicyV3 extends WeeklySnapshotPolicyV2 {

    public static final Versions VERSIONS = new Versions(
            1,
            "weekly-metrics-v3",
            "weekly-snapshot-v6",
            "weekly-quality-v3"
    );

    static final int MAX_CATEGORY_DIRECTIONS = 2;
    static final int MAX_BENCHMARK_CATEGORIES = 3;
    static final int MAX_LEARNERS = 3;

    private static final BigDecimal STORE_RELATIVE_DELTA_PERCENT =
            new BigDecimal("5.00");
    private static final BigDecimal EMPLOYEE_RELATIVE_DELTA_PERCENT =
            new BigDecimal("10.00");
    private static final BigDecimal CATEGORY_RELATIVE_DELTA_PERCENT =
            new BigDecimal("15.00");
    private static final BigDecimal CATEGORY_STORE_SHARE_PERCENT =
            new BigDecimal("3.00");
    private static final BigDecimal PLAN_GAP_PERCENTAGE_POINTS =
            new BigDecimal("5.00");
    private static final BigDecimal ATTACH_GAP_PER_HUNDRED =
            new BigDecimal("5.00");
    private static final BigDecimal SHARE_GAP_PERCENTAGE_POINTS =
            new BigDecimal("3.00");
    private static final BigDecimal SCORE_GAP_POINTS =
            new BigDecimal("5.00");
    private static final BigDecimal MINIMUM_ATTACH_DENOMINATOR =
            new BigDecimal("5.00");

    boolean materialStoreDelta(BigDecimal relativeDeltaPercent) {
        return reaches(relativeDeltaPercent, STORE_RELATIVE_DELTA_PERCENT);
    }

    boolean materialEmployeeRelativeDelta(BigDecimal relativeDeltaPercent) {
        return reaches(relativeDeltaPercent, EMPLOYEE_RELATIVE_DELTA_PERCENT);
    }

    boolean materialCategoryDelta(
            BigDecimal relativeDeltaPercent,
            BigDecimal currentSharePercent,
            BigDecimal previousSharePercent
    ) {
        return reaches(relativeDeltaPercent, CATEGORY_RELATIVE_DELTA_PERCENT)
                && maximum(currentSharePercent, previousSharePercent)
                .compareTo(CATEGORY_STORE_SHARE_PERCENT) >= 0;
    }

    boolean materialPlanGap(BigDecimal projectedCompletionPercent) {
        return projectedCompletionPercent != null
                && projectedCompletionPercent.subtract(BigDecimal.valueOf(100))
                .abs().compareTo(PLAN_GAP_PERCENTAGE_POINTS) >= 0;
    }

    boolean materialAttachGap(BigDecimal absoluteDelta) {
        return reaches(absoluteDelta, ATTACH_GAP_PER_HUNDRED);
    }

    boolean materialShareGap(BigDecimal absoluteDelta) {
        return reaches(absoluteDelta, SHARE_GAP_PERCENTAGE_POINTS);
    }

    boolean materialScoreGap(BigDecimal absoluteDelta) {
        return reaches(absoluteDelta, SCORE_GAP_POINTS);
    }

    boolean sufficientAttachPair(BigDecimal current, BigDecimal previous) {
        return current != null && previous != null
                && current.compareTo(MINIMUM_ATTACH_DENOMINATOR) >= 0
                && previous.compareTo(MINIMUM_ATTACH_DENOMINATOR) >= 0;
    }

    private boolean reaches(BigDecimal value, BigDecimal threshold) {
        return value != null && value.abs().compareTo(threshold) >= 0;
    }

    private BigDecimal maximum(BigDecimal first, BigDecimal second) {
        BigDecimal left = first == null ? BigDecimal.ZERO : first;
        BigDecimal right = second == null ? BigDecimal.ZERO : second;
        return left.max(right);
    }
}
