package com.storeanalytics.interpretation.review.ai;

import com.storeanalytics.integration.llm.yandex.YandexLlmProperties;
import com.storeanalytics.interpretation.review.SellerWeeklyReviewService;
import com.storeanalytics.interpretation.review.SellerWeeklyReviewView;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.ReportState;
import com.storeanalytics.interpretation.snapshot.WeeklySnapshotPlanningStore;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class WeeklyReviewAiPlanningService {

    private static final Logger LOGGER = LoggerFactory.getLogger(WeeklyReviewAiPlanningService.class);

    private final WeeklyReviewAiJobStore jobStore;
    private final WeeklyReviewAiGenerationProperties properties;
    private final YandexLlmProperties yandexProperties;
    private final Clock clock;
    private final WeeklySnapshotPlanningStore stores;
    private final SellerWeeklyReviewService sellerReviews;
    private UUID cursor;

    public WeeklyReviewAiPlanningService(
            WeeklyReviewAiJobStore jobStore,
            WeeklyReviewAiGenerationProperties properties,
            YandexLlmProperties yandexProperties,
            Clock clock
    ) {
        this(jobStore, properties, yandexProperties, clock, null, null);
    }

    @Autowired
    public WeeklyReviewAiPlanningService(WeeklyReviewAiJobStore jobStore,
                                        WeeklyReviewAiGenerationProperties properties,
                                        YandexLlmProperties yandexProperties, Clock clock,
                                        WeeklySnapshotPlanningStore stores, SellerWeeklyReviewService sellerReviews) {
        this.jobStore = jobStore;
        this.properties = properties;
        this.yandexProperties = yandexProperties;
        this.clock = clock;
        this.stores = stores;
        this.sellerReviews = sellerReviews;
    }

    public synchronized int plan() {
        if (jobStore.activeReportContractVersion() == 3) {
            return planSellers();
        }
        return jobStore.enqueueLatest(
                properties.providerCode(),
                yandexProperties.getModelUri(),
                properties.maxProviderCalls(),
                properties.batchSize(),
                clock.instant(),
                properties.jobDeadline()
        );
    }

    private int planSellers() {
        if (stores == null || sellerReviews == null) {
            throw new IllegalStateException("Seller AI planning requires the stable seller reader");
        }
        var page = stores.activeStoresAfter(cursor, properties.batchSize());
        int created = 0;
        UUID next = cursor;
        for (var target : page) {
            try {
                created += planSeller(target.storeId());
            } catch (RuntimeException failure) {
                LOGGER.error("Seller AI planning deferred store; store_id={} failure_type={}",
                        target.storeId(), failure.getClass().getSimpleName());
            }
            next = target.storeId();
        }
        cursor = page.size() < properties.batchSize() ? null : next;
        return created;
    }

    private int planSeller(UUID storeId) {
        var review = sellerReviews.current(storeId);
        if (review.freshness() == SellerWeeklyReviewView.Freshness.CURRENT
                && (review.report().reportState() == ReportState.READY
                    || review.report().reportState() == ReportState.PARTIAL)
                && review.report().aiEnhancement().state()
                    != com.storeanalytics.interpretation.review.WeeklyReviewResponse.AiState.READY) {
            UUID snapshotId = UUID.fromString(review.report().provenance().snapshotPublicId());
            if (jobStore.enqueueAutomaticSellerWeek(snapshotId, properties.providerCode(),
                    yandexProperties.getModelUri(), properties.maxProviderCalls(),
                    clock.instant(), properties.jobDeadline())) {
                return 1;
            }
        }
        return 0;
    }
}
