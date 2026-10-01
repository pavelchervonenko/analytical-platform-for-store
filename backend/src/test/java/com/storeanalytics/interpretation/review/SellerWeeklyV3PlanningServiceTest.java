package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class SellerWeeklyV3PlanningServiceTest {

    private static final String ZONE = "Europe/Kaliningrad";

    private final UUID storeId = UUID.randomUUID();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SellerWeeklyV3ReadService reads = mock(SellerWeeklyV3ReadService.class);
    private final SellerWeeklyV3CandidateService candidates = mock(SellerWeeklyV3CandidateService.class);
    private final SellerWeeklyV3PlanningService service =
            new SellerWeeklyV3PlanningService(jdbc, reads, candidates);
    private final PersistedWeeklyReviewV3Snapshot saved = mock(PersistedWeeklyReviewV3Snapshot.class);

    @Test
    void currentIdentitySkipsGenerationAndDoesNotAdvanceCheckpoint() {
        SellerWeeklyV3ReadResult current = review(SellerWeeklyV3ReadResult.State.CURRENT);
        when(reads.assessForPlanning(storeId)).thenReturn(current);

        SellerWeeklyV3PlanningResult result = service.evaluate(storeId);

        assertThat(result.outcome()).isEqualTo(SellerWeeklyV3PlanningResult.Outcome.UNCHANGED);
        assertThat(result.review()).isSameAs(current);
        verify(reads).assessForPlanning(storeId);
        verifyNoInteractions(jdbc, candidates);
    }

    @Test
    void preparingStateGeneratesUsingAuthoritativeTimezoneThenReassesses() {
        SellerWeeklyV3ReadResult current = review(SellerWeeklyV3ReadResult.State.CURRENT);
        when(reads.assessForPlanning(storeId)).thenReturn(preparing(), current);
        timezone();
        when(candidates.generateStableCandidate(storeId, ZONE)).thenReturn(Optional.of(saved));

        SellerWeeklyV3PlanningResult result = service.evaluate(storeId);

        assertThat(result.outcome()).isEqualTo(SellerWeeklyV3PlanningResult.Outcome.EVALUATED);
        assertThat(result.review()).isSameAs(current);
        verify(reads, times(2)).assessForPlanning(storeId);
        verify(candidates).generateStableCandidate(storeId, ZONE);
    }

    @Test
    void incompleteOrUnstableSourceKeepsPreviousCompatibleSnapshot() {
        SellerWeeklyV3ReadResult stale = review(SellerWeeklyV3ReadResult.State.STALE);
        when(reads.assessForPlanning(storeId)).thenReturn(stale);
        timezone();
        when(candidates.generateStableCandidate(storeId, ZONE)).thenReturn(Optional.empty());

        SellerWeeklyV3PlanningResult result = service.evaluate(storeId);

        assertThat(result.outcome()).isEqualTo(SellerWeeklyV3PlanningResult.Outcome.DEFERRED);
        assertThat(result.review().snapshot()).containsSame(saved);
        assertThat(result.review().state()).isEqualTo(SellerWeeklyV3ReadResult.State.STALE);
        verify(candidates).generateStableCandidate(storeId, ZONE);
    }

    @Test
    void exhaustedSourceConflictsDeferWithoutAnotherGenerationLoop() {
        SellerWeeklyV3ReadResult stale = review(SellerWeeklyV3ReadResult.State.STALE);
        when(reads.assessForPlanning(storeId)).thenReturn(stale);
        timezone();
        when(candidates.generateStableCandidate(storeId, ZONE))
                .thenThrow(new SellerWeeklySourceChangedException());

        SellerWeeklyV3PlanningResult result = service.evaluate(storeId);

        assertThat(result.outcome()).isEqualTo(SellerWeeklyV3PlanningResult.Outcome.DEFERRED);
        assertThat(result.review()).isSameAs(stale);
        verify(candidates).generateStableCandidate(storeId, ZONE);
        verify(reads, times(2)).assessForPlanning(storeId);
    }

    @Test
    void aWriteDoesNotGuaranteeCurrentStateAfterAConcurrentChange() {
        SellerWeeklyV3ReadResult stale = review(SellerWeeklyV3ReadResult.State.STALE);
        when(reads.assessForPlanning(storeId)).thenReturn(stale);
        timezone();
        when(candidates.generateStableCandidate(storeId, ZONE)).thenReturn(Optional.of(saved));

        SellerWeeklyV3PlanningResult result = service.evaluate(storeId);

        assertThat(result.outcome()).isEqualTo(SellerWeeklyV3PlanningResult.Outcome.EVALUATED);
        assertThat(result.review().state()).isEqualTo(SellerWeeklyV3ReadResult.State.STALE);
    }

    @Test
    void weekRolloverUsesNewAssessmentInsteadOfReturningTheOldSnapshot() {
        SellerWeeklyV3ReadResult stale = review(SellerWeeklyV3ReadResult.State.STALE);
        when(reads.assessForPlanning(storeId)).thenReturn(stale, preparing());
        timezone();
        when(candidates.generateStableCandidate(storeId, ZONE)).thenReturn(Optional.empty());

        SellerWeeklyV3PlanningResult result = service.evaluate(storeId);

        assertThat(result.outcome()).isEqualTo(SellerWeeklyV3PlanningResult.Outcome.DEFERRED);
        assertThat(result.review().state()).isEqualTo(SellerWeeklyV3ReadResult.State.PREPARING);
        assertThat(result.review().snapshot()).isEmpty();
    }

    @Test
    void infrastructureErrorsAreNotHiddenAsSyncDeferral() {
        when(reads.assessForPlanning(storeId)).thenReturn(preparing());
        timezone();
        when(candidates.generateStableCandidate(storeId, ZONE))
                .thenThrow(new DataAccessResourceFailureException("Synthetic connection failure"));

        assertThatThrownBy(() -> service.evaluate(storeId))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verify(reads).assessForPlanning(storeId);
    }

    private SellerWeeklyV3ReadResult preparing() {
        return new SellerWeeklyV3ReadResult(SellerWeeklyV3ReadResult.State.PREPARING, Optional.empty());
    }

    private SellerWeeklyV3ReadResult review(SellerWeeklyV3ReadResult.State state) {
        return new SellerWeeklyV3ReadResult(state, Optional.of(saved));
    }

    private void timezone() {
        when(jdbc.queryForObject("SELECT timezone FROM stores WHERE id = ?", String.class, storeId))
                .thenReturn(ZONE);
    }
}
