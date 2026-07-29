package com.storeanalytics.common.idempotency;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.BackgroundSchedulingConfiguration;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import java.time.Clock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnApplicationRole({ApplicationRole.WORKER, ApplicationRole.COMBINED})
public class IdempotencyReceiptCleanup {

    private final IdempotencyReceiptRepository repository;
    private final IdempotencyProperties properties;
    private final Clock clock;

    public IdempotencyReceiptCleanup(
            IdempotencyReceiptRepository repository,
            IdempotencyProperties properties,
            Clock clock
    ) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(
            cron = "${app.idempotency.cleanup-cron:0 15 4 * * *}",
            zone = "UTC",
            scheduler = BackgroundSchedulingConfiguration.CLEANUP_SCHEDULER
    )
    @Transactional
    public int deleteExpiredReceipts() {
        return repository.deleteExpiredBatch(
                clock.instant(),
                properties.cleanupBatchSize()
        );
    }
}
