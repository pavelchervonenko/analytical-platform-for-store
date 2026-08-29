package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.review.WeeklyReviewPolicyV1.RevenuePeriod;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.PeriodContext;
import com.storeanalytics.interpretation.snapshot.EmployeeSalesSampleFacts;
import com.storeanalytics.metrics.service.AttachRateResult;
import com.storeanalytics.metrics.service.CategoryKpiResult;
import com.storeanalytics.metrics.service.StoreKpiResult;
import com.storeanalytics.performance.service.EmployeeRatingResult;
import com.storeanalytics.store.service.StoreDataStatusView;
import java.time.Instant;
import java.util.UUID;

/** Complete plan-free source material used to build one deterministic weekly review. */
public record WeeklyReviewFacts(
        UUID storeId,
        PeriodContext period,
        StoreDataStatusView sourceDataStatus,
        PeriodFacts current,
        PeriodFacts previous,
        Instant sourceDataUpdatedAt
) {

    public WeeklyReviewFacts {
        requireNonNull(storeId, "storeId");
        requireNonNull(period, "period");
        requireNonNull(sourceDataStatus, "sourceDataStatus");
        requireNonNull(current, "current");
        requireNonNull(previous, "previous");
    }

    public record PeriodFacts(
            StoreKpiResult store,
            CategoryKpiResult categories,
            AttachRateResult attachRates,
            EmployeeRatingResult employeeFacts,
            EmployeeSalesSampleFacts employeeSalesSamples,
            long unattributedReturnDocumentCount,
            RevenuePeriod revenue
    ) {

        public PeriodFacts {
            requireNonNull(store, "store");
            requireNonNull(categories, "categories");
            requireNonNull(attachRates, "attachRates");
            requireNonNull(employeeFacts, "employeeFacts");
            requireNonNull(employeeSalesSamples, "employeeSalesSamples");
            if (unattributedReturnDocumentCount < 0) {
                throw new IllegalArgumentException(
                        "unattributedReturnDocumentCount must not be negative"
                );
            }
            requireNonNull(revenue, "revenue");
        }
    }
}
