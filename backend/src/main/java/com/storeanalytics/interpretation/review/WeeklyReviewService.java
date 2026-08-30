package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.review.ai.WeeklyReviewAiReadSupport;
import com.storeanalytics.metrics.exception.StoreNotFoundException;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.repository.StoreRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class WeeklyReviewService {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            WeeklyReviewService.class
    );

    private final StoreRepository storeRepository;
    private final WeeklyReviewFactsSource factsSource;
    private final WeeklyReviewSnapshotStore snapshotStore;
    private final WeeklyReviewAiReadSupport aiSupport;
    private final Clock clock;
    private final WeeklyReviewPolicyV1 policy = new WeeklyReviewPolicyV1();

    public WeeklyReviewService(
            StoreRepository storeRepository,
            WeeklyReviewFactsSource factsSource,
            WeeklyReviewSnapshotStore snapshotStore,
            WeeklyReviewAiReadSupport aiSupport,
            Clock clock
    ) {
        this.storeRepository = storeRepository;
        this.factsSource = factsSource;
        this.snapshotStore = snapshotStore;
        this.aiSupport = aiSupport;
        this.clock = clock;
    }

    public PersistedWeeklyReviewSnapshot generate(UUID storeId) {
        Store store = store(storeId);
        Instant now = clock.instant();
        WeeklyReviewFacts facts = factsSource.load(
                store.getId(), now, store.getTimezone()
        );
        return snapshotStore.persist(facts, now);
    }

    public Optional<WeeklyReviewResponse> current(UUID storeId) {
        Store store = store(storeId);
        var period = policy.period(clock.instant(), store.getTimezone());
        return snapshotStore.findLatest(store.getId(), period.current())
                .map(this::withPublishedAiEnrichment);
    }

    private WeeklyReviewResponse withPublishedAiEnrichment(
            PersistedWeeklyReviewSnapshot snapshot
    ) {
        if (!aiSupport.properties().enabled()) {
            return aiSupport.stateResolver().apply(snapshot, clock.instant());
        }
        Instant now = clock.instant();
        try {
            for (var value : aiSupport.enrichmentStore()
                    .findPublishedCandidates(snapshot.id(), now)) {
                try {
                    var applied = aiSupport.enricher().applyIfCompatible(
                            snapshot.response(),
                            value.validationResult(),
                            value.publishedAt(),
                            value.promptVersion(),
                            value.contentSchemaVersion()
                    );
                    if (applied.isPresent()) {
                        return applied.get();
                    }
                    LOGGER.error(
                            "Ignoring incompatible weekly review AI "
                                    + "enrichment for snapshot {} and prompt {}",
                            snapshot.id(), value.promptVersion()
                    );
                } catch (IllegalArgumentException | IllegalStateException exception) {
                    LOGGER.error(
                            "Ignoring invalid weekly review AI enrichment "
                                    + "for snapshot {} and prompt {}",
                            snapshot.id(), value.promptVersion(), exception
                    );
                }
            }
        } catch (IllegalArgumentException | IllegalStateException exception) {
            LOGGER.error(
                    "Ignoring weekly review AI read failure for snapshot {}",
                    snapshot.id(), exception
            );
        }
        return aiSupport.stateResolver().apply(snapshot, now);
    }

    private Store store(UUID storeId) {
        UUID validated = requireNonNull(storeId, "storeId");
        return storeRepository.findById(validated)
                .orElseThrow(() -> new StoreNotFoundException(validated));
    }
}
