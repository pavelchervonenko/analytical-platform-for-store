package com.storeanalytics.interpretation.review;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnApplicationRole({ApplicationRole.WORKER, ApplicationRole.COMBINED})
@ConditionalOnProperty(prefix = "app.interpretation.seller-weekly-preparation", name = "enabled", havingValue = "true")
class SellerWeeklyPreparationScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(SellerWeeklyPreparationScheduler.class);
    private final SellerWeeklyPreparationBatchService service;

    SellerWeeklyPreparationScheduler(SellerWeeklyPreparationBatchService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.interpretation.seller-weekly-preparation.scan-delay:1m}",
            scheduler = SellerWeeklyPreparationSchedulingConfiguration.SCHEDULER)
    void reconcile() {
        try {
            var result = service.reconcile();
            // Source/history waits and unchanged scheduler ticks must not produce repeated log noise.
            if (result.discovered() > 0 || result.reopened() > 0 || result.prepared() > 0 || result.failures() > 0) {
                LOGGER.info("Seller weekly free preparation; discovered={} reopened={} prepared={} failures={}",
                        result.discovered(), result.reopened(), result.prepared(), result.failures());
            }
        } catch (RuntimeException failure) {
            LOGGER.error("Seller weekly free preparation iteration failed; failure_type={}",
                    failure.getClass().getSimpleName());
        }
    }
}
