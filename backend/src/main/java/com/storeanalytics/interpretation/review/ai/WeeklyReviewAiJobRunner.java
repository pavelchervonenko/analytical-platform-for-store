package com.storeanalytics.interpretation.review.ai;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class WeeklyReviewAiJobRunner {

    private final WeeklyReviewAiJobStore jobStore;
    private final WeeklyReviewAiGenerationExecutionService executionService;
    private final WeeklyReviewAiGenerationProperties properties;
    private final Clock clock;
    private final AtomicReference<ActiveLease> active = new AtomicReference<>();

    public WeeklyReviewAiJobRunner(
            WeeklyReviewAiJobStore jobStore,
            WeeklyReviewAiGenerationExecutionService executionService,
            WeeklyReviewAiGenerationProperties properties,
            Clock clock
    ) {
        this.jobStore = jobStore;
        this.executionService = executionService;
        this.properties = properties;
        this.clock = clock;
    }

    public Optional<WeeklyReviewAiJob> runNext(String owner) {
        Optional<WeeklyReviewAiJob> claimed = jobStore.claimNext(
                owner, properties.leaseDuration(), clock.instant()
        );
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        WeeklyReviewAiJob job = claimed.get();
        ActiveLease lease = new ActiveLease(job.id(), owner);
        if (!active.compareAndSet(null, lease)) {
            throw new IllegalStateException(
                    "Weekly review AI runner already owns a job"
            );
        }
        try {
            executionService.execute(job, owner);
            return jobStore.findById(job.id());
        } finally {
            active.compareAndSet(lease, null);
        }
    }

    public boolean heartbeatCurrent() {
        ActiveLease lease = active.get();
        return lease == null || jobStore.heartbeat(
                lease.jobId(),
                lease.owner(),
                properties.leaseDuration(),
                clock.instant()
        );
    }

    private record ActiveLease(java.util.UUID jobId, String owner) {
    }
}
