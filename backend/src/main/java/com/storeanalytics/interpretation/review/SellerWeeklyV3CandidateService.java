package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Internal-only bounded retry; the public v2 service and planner do not call this service. */
@Service
class SellerWeeklyV3CandidateService {

    private static final int MAX_ATTEMPTS = 3;

    private final SellerWeeklyReviewFactsSource factsSource;
    private final SellerWeeklyIdentityFactsSource metadataSource;
    private final SellerWeeklySourceIdentity identity;
    private final WeeklyReviewSnapshotStore snapshots;
    private final Clock clock;

    SellerWeeklyV3CandidateService(SellerWeeklyReviewFactsSource factsSource,
                                   SellerWeeklyIdentityFactsSource metadataSource,
                                   SellerWeeklySourceIdentity identity,
                                   WeeklyReviewSnapshotStore snapshots, Clock clock) {
        this.factsSource = factsSource;
        this.metadataSource = metadataSource;
        this.identity = identity;
        this.snapshots = snapshots;
        this.clock = clock;
    }

    PersistedWeeklyReviewV3Snapshot generateCandidate(UUID storeId, String timezone) {
        return generate(storeId, timezone, false).orElseThrow();
    }

    Optional<PersistedWeeklyReviewV3Snapshot> generateStableCandidate(UUID storeId, String timezone) {
        return generate(storeId, timezone, true);
    }

    private Optional<PersistedWeeklyReviewV3Snapshot> generate(
            UUID storeId, String timezone, boolean requireStableSources) {
        UUID selectedStore = requireNonNull(storeId, "storeId");
        String selectedTimezone = requireNonNull(timezone, "timezone");
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Instant now = clock.instant();
            if (requireStableSources) {
                SellerWeeklyIdentityFacts preflight = metadataSource.load(selectedStore, now, selectedTimezone);
                if (preflight.sourceStability() != SellerWeeklySourceStability.STABLE
                        || !preflight.sourceCoverage().completeBothWeeks()) {
                    return Optional.empty();
                }
            }
            SellerWeeklyReviewFacts facts = factsSource.load(selectedStore, now, selectedTimezone);
            // Preflight is only a cheap deferral: it never authorizes a write from another MVCC snapshot.
            if (requireStableSources && (facts.sourceStability() != SellerWeeklySourceStability.STABLE
                    || !facts.sourceCoverage().completeBothWeeks())) {
                return Optional.empty();
            }
            try {
                return Optional.of(snapshots.persistV3Candidate(facts, now, identity.hash(facts)));
            } catch (SellerWeeklySourceChangedException changed) {
                if (attempt == MAX_ATTEMPTS - 1) {
                    throw changed;
                }
            }
        }
        throw new IllegalStateException("Unreachable seller weekly retry state");
    }
}
