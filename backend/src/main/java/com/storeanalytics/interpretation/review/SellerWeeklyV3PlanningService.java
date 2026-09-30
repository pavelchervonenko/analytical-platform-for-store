package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.interpretation.review.SellerWeeklyV3PlanningResult.Outcome.DEFERRED;
import static com.storeanalytics.interpretation.review.SellerWeeklyV3PlanningResult.Outcome.EVALUATED;
import static com.storeanalytics.interpretation.review.SellerWeeklyV3PlanningResult.Outcome.UNCHANGED;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Internal, unscheduled v3 orchestration; never called by the public v2 planner or API. */
@Service
class SellerWeeklyV3PlanningService {

    private final JdbcTemplate jdbc;
    private final SellerWeeklyV3ReadService reads;
    private final SellerWeeklyV3CandidateService candidates;

    SellerWeeklyV3PlanningService(JdbcTemplate jdbc, SellerWeeklyV3ReadService reads,
                                  SellerWeeklyV3CandidateService candidates) {
        this.jdbc = jdbc;
        this.reads = reads;
        this.candidates = candidates;
    }

    // RR assessments and the fenced RC write must remain separate transactions.
    @Transactional(propagation = Propagation.NEVER)
    SellerWeeklyV3PlanningResult evaluate(UUID storeId) {
        UUID selectedStore = requireNonNull(storeId, "storeId");
        SellerWeeklyV3ReadResult before = reads.assessForPlanning(selectedStore);
        if (before.state() == SellerWeeklyV3ReadResult.State.CURRENT) {
            return new SellerWeeklyV3PlanningResult(UNCHANGED, before);
        }
        String timezone = jdbc.queryForObject("SELECT timezone FROM stores WHERE id = ?",
                String.class, selectedStore);
        if (timezone == null) {
            throw new IllegalArgumentException("Store does not exist: " + selectedStore);
        }
        SellerWeeklyV3PlanningResult.Outcome outcome;
        try {
            outcome = candidates.generateStableCandidate(selectedStore, timezone).isPresent()
                    ? EVALUATED : DEFERRED;
        } catch (SellerWeeklySourceChangedException changed) {
            // The candidate service has exhausted its bounded retries; wait for another scan.
            outcome = DEFERRED;
        }
        return new SellerWeeklyV3PlanningResult(outcome, reads.assessForPlanning(selectedStore));
    }
}
