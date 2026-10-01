package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;

class SellerWeeklyV3CandidateServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-24T04:00:00Z");
    private static final String ZONE = "Europe/Kaliningrad";

    private final SellerWeeklyReviewFactsSource source = mock(SellerWeeklyReviewFactsSource.class);
    private final SellerWeeklyIdentityFactsSource metadataSource = mock(SellerWeeklyIdentityFactsSource.class);
    private final SellerWeeklySourceIdentity identity = mock(SellerWeeklySourceIdentity.class);
    private final WeeklyReviewSnapshotStore snapshots = mock(WeeklyReviewSnapshotStore.class);
    private final SellerWeeklyV3CandidateService service = new SellerWeeklyV3CandidateService(
            source, metadataSource, identity, snapshots, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void readyMetadataDoesNotReplaceTheFullFactsGate() {
        SellerWeeklyIdentityFacts metadata = readyMetadata();
        when(metadataSource.load(any(), any(), any())).thenReturn(metadata);
    }

    @Test
    void retriesWithACompleteFreshFactsReadAfterSourceRevisionConflict() {
        UUID storeId = UUID.randomUUID();
        SellerWeeklyReviewFacts oldFacts = mock(SellerWeeklyReviewFacts.class);
        SellerWeeklyReviewFacts freshFacts = mock(SellerWeeklyReviewFacts.class);
        PersistedWeeklyReviewV3Snapshot saved = mock(PersistedWeeklyReviewV3Snapshot.class);
        when(source.load(storeId, NOW, ZONE)).thenReturn(oldFacts, freshFacts);
        when(identity.hash(oldFacts)).thenReturn("a".repeat(64));
        when(identity.hash(freshFacts)).thenReturn("b".repeat(64));
        when(snapshots.persistV3Candidate(oldFacts, NOW, "a".repeat(64)))
                .thenThrow(new SellerWeeklySourceChangedException());
        when(snapshots.persistV3Candidate(freshFacts, NOW, "b".repeat(64))).thenReturn(saved);

        assertThat(service.generateCandidate(storeId, ZONE)).isSameAs(saved);
        verify(source, times(2)).load(storeId, NOW, ZONE);
        verify(snapshots).persistV3Candidate(freshFacts, NOW, "b".repeat(64));
    }

    @Test
    void stopsAfterThreeConflictsWithoutPublishingAnything() {
        UUID storeId = UUID.randomUUID();
        SellerWeeklyReviewFacts facts = mock(SellerWeeklyReviewFacts.class);
        when(source.load(storeId, NOW, ZONE)).thenReturn(facts);
        when(identity.hash(facts)).thenReturn("a".repeat(64));
        when(snapshots.persistV3Candidate(facts, NOW, "a".repeat(64)))
                .thenThrow(new SellerWeeklySourceChangedException());

        assertThatThrownBy(() -> service.generateCandidate(storeId, ZONE))
                .isInstanceOf(SellerWeeklySourceChangedException.class);
        verify(source, times(3)).load(storeId, NOW, ZONE);
        verify(snapshots, times(3)).persistV3Candidate(facts, NOW, "a".repeat(64));
    }

    @ParameterizedTest
    @EnumSource(value = SellerWeeklySourceStability.class,
            names = {"IN_PROGRESS", "NEEDS_RECONCILIATION"})
    void stableGenerationDefersUnstableSourceWithoutHashingOrWriting(
            SellerWeeklySourceStability stability) {
        UUID storeId = UUID.randomUUID();
        SellerWeeklyReviewFacts facts = mock(SellerWeeklyReviewFacts.class);
        when(source.load(storeId, NOW, ZONE)).thenReturn(facts);
        when(facts.sourceStability()).thenReturn(stability);

        assertThat(service.generateStableCandidate(storeId, ZONE)).isEmpty();
        verifyNoInteractions(identity, snapshots);
    }

    @ParameterizedTest
    @CsvSource({
            "false,true,true,true,true,true",
            "true,false,true,true,true,true",
            "true,true,false,true,true,true",
            "true,true,true,false,true,true",
            "true,true,true,true,false,true",
            "true,true,true,true,true,false",
            "false,false,false,false,false,false"
    })
    void stableGenerationRequiresEverySourceInBothWeeks(boolean salesCurrent, boolean salesPrevious,
            boolean returnsCurrent, boolean returnsPrevious, boolean ordersCurrent, boolean ordersPrevious) {
        UUID storeId = UUID.randomUUID();
        SellerWeeklyReviewFacts facts = mock(SellerWeeklyReviewFacts.class);
        when(source.load(storeId, NOW, ZONE)).thenReturn(facts);
        when(facts.sourceStability()).thenReturn(SellerWeeklySourceStability.STABLE);
        when(facts.sourceCoverage()).thenReturn(new SellerWeeklySourceCoverage(
                new SellerWeeklySourceCoverage.Window(salesCurrent, salesPrevious),
                new SellerWeeklySourceCoverage.Window(returnsCurrent, returnsPrevious),
                new SellerWeeklySourceCoverage.Window(ordersCurrent, ordersPrevious)));

        assertThat(service.generateStableCandidate(storeId, ZONE)).isEmpty();
        verifyNoInteractions(identity, snapshots);
    }

    @Test
    void stableGenerationWritesOnlyAfterPassingTheGate() {
        UUID storeId = UUID.randomUUID();
        SellerWeeklyReviewFacts facts = stableFacts();
        PersistedWeeklyReviewV3Snapshot saved = mock(PersistedWeeklyReviewV3Snapshot.class);
        when(source.load(storeId, NOW, ZONE)).thenReturn(facts);
        when(identity.hash(facts)).thenReturn("a".repeat(64));
        when(snapshots.persistV3Candidate(facts, NOW, "a".repeat(64))).thenReturn(saved);

        assertThat(service.generateStableCandidate(storeId, ZONE)).containsSame(saved);
        verify(snapshots).persistV3Candidate(facts, NOW, "a".repeat(64));
    }

    @Test
    void stableGenerationRechecksEligibilityAfterAConflict() {
        UUID storeId = UUID.randomUUID();
        SellerWeeklyReviewFacts facts = stableFacts();
        SellerWeeklyReviewFacts syncing = mock(SellerWeeklyReviewFacts.class);
        when(syncing.sourceStability()).thenReturn(SellerWeeklySourceStability.IN_PROGRESS);
        when(source.load(storeId, NOW, ZONE)).thenReturn(facts, syncing);
        when(identity.hash(facts)).thenReturn("a".repeat(64));
        when(snapshots.persistV3Candidate(facts, NOW, "a".repeat(64)))
                .thenThrow(new SellerWeeklySourceChangedException());

        assertThat(service.generateStableCandidate(storeId, ZONE)).isEmpty();
        verify(source, times(2)).load(storeId, NOW, ZONE);
        verify(snapshots).persistV3Candidate(facts, NOW, "a".repeat(64));
        verify(identity).hash(facts);
    }

    @Test
    void stableGenerationAlsoStopsAfterThreeConflicts() {
        UUID storeId = UUID.randomUUID();
        SellerWeeklyReviewFacts facts = stableFacts();
        when(source.load(storeId, NOW, ZONE)).thenReturn(facts);
        when(identity.hash(facts)).thenReturn("a".repeat(64));
        when(snapshots.persistV3Candidate(facts, NOW, "a".repeat(64)))
                .thenThrow(new SellerWeeklySourceChangedException());

        assertThatThrownBy(() -> service.generateStableCandidate(storeId, ZONE))
                .isInstanceOf(SellerWeeklySourceChangedException.class);
        verify(source, times(3)).load(storeId, NOW, ZONE);
        verify(snapshots, times(3)).persistV3Candidate(facts, NOW, "a".repeat(64));
    }

    @ParameterizedTest
    @EnumSource(value = SellerWeeklySourceStability.class,
            names = {"IN_PROGRESS", "NEEDS_RECONCILIATION"})
    void unstableMetadataDefersWithoutReadingFinancialFacts(SellerWeeklySourceStability stability) {
        UUID storeId = UUID.randomUUID();
        SellerWeeklyIdentityFacts metadata = readyMetadata();
        when(metadata.sourceStability()).thenReturn(stability);
        when(metadataSource.load(storeId, NOW, ZONE)).thenReturn(metadata);

        assertThat(service.generateStableCandidate(storeId, ZONE)).isEmpty();
        verifyNoInteractions(source, identity, snapshots);
    }

    @ParameterizedTest
    @CsvSource({
            "false,true,true,true,true,true",
            "true,false,true,true,true,true",
            "true,true,false,true,true,true",
            "true,true,true,false,true,true",
            "true,true,true,true,false,true",
            "true,true,true,true,true,false",
            "false,false,false,false,false,false"
    })
    void incompleteMetadataDefersBeforeFinancialQueries(boolean salesCurrent, boolean salesPrevious,
            boolean returnsCurrent, boolean returnsPrevious, boolean ordersCurrent, boolean ordersPrevious) {
        UUID storeId = UUID.randomUUID();
        SellerWeeklyIdentityFacts metadata = readyMetadata();
        when(metadata.sourceCoverage()).thenReturn(new SellerWeeklySourceCoverage(
                new SellerWeeklySourceCoverage.Window(salesCurrent, salesPrevious),
                new SellerWeeklySourceCoverage.Window(returnsCurrent, returnsPrevious),
                new SellerWeeklySourceCoverage.Window(ordersCurrent, ordersPrevious)));
        when(metadataSource.load(storeId, NOW, ZONE)).thenReturn(metadata);

        assertThat(service.generateStableCandidate(storeId, ZONE)).isEmpty();
        verifyNoInteractions(source, identity, snapshots);
    }

    @Test
    void metadataIsReadAgainAfterAConflictAndCanStopTheNextFinancialRead() {
        UUID storeId = UUID.randomUUID();
        SellerWeeklyReviewFacts facts = stableFacts();
        SellerWeeklyIdentityFacts syncing = readyMetadata();
        when(syncing.sourceStability()).thenReturn(SellerWeeklySourceStability.IN_PROGRESS);
        SellerWeeklyIdentityFacts ready = readyMetadata();
        when(metadataSource.load(storeId, NOW, ZONE)).thenReturn(ready, syncing);
        when(source.load(storeId, NOW, ZONE)).thenReturn(facts);
        when(identity.hash(facts)).thenReturn("a".repeat(64));
        when(snapshots.persistV3Candidate(facts, NOW, "a".repeat(64)))
                .thenThrow(new SellerWeeklySourceChangedException());

        assertThat(service.generateStableCandidate(storeId, ZONE)).isEmpty();
        verify(metadataSource, times(2)).load(storeId, NOW, ZONE);
        verify(source).load(storeId, NOW, ZONE);
        verify(snapshots).persistV3Candidate(facts, NOW, "a".repeat(64));
    }

    @Test
    void metadataFailureIsNotSilentlyConvertedToSourceDeferral() {
        UUID storeId = UUID.randomUUID();
        var failure = new DataAccessResourceFailureException("Synthetic metadata unavailable");
        when(metadataSource.load(storeId, NOW, ZONE)).thenThrow(failure);
        assertThatThrownBy(() -> service.generateStableCandidate(storeId, ZONE)).isSameAs(failure);
        verifyNoInteractions(source, identity, snapshots);
    }

    private SellerWeeklyIdentityFacts readyMetadata() {
        SellerWeeklyIdentityFacts metadata = mock(SellerWeeklyIdentityFacts.class);
        when(metadata.sourceStability()).thenReturn(SellerWeeklySourceStability.STABLE);
        when(metadata.sourceCoverage()).thenReturn(SellerWeeklySourceCoverage.complete());
        return metadata;
    }

    private SellerWeeklyReviewFacts stableFacts() {
        SellerWeeklyReviewFacts facts = mock(SellerWeeklyReviewFacts.class);
        when(facts.sourceStability()).thenReturn(SellerWeeklySourceStability.STABLE);
        when(facts.sourceCoverage()).thenReturn(SellerWeeklySourceCoverage.complete());
        return facts;
    }
}
