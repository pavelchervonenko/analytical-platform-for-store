package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse.DateRange;
import com.storeanalytics.interpretation.review.ai.PersistedWeeklyReviewAiEnrichment;
import com.storeanalytics.interpretation.review.ai.WeeklyReviewAiContent;
import com.storeanalytics.interpretation.review.ai.WeeklyReviewAiEnricher;
import com.storeanalytics.interpretation.review.ai.WeeklyReviewAiEnrichmentStore;
import com.storeanalytics.interpretation.review.ai.WeeklyReviewAiReadSupport;
import com.storeanalytics.interpretation.review.ai.WeeklyReviewAiStateResolver;
import com.storeanalytics.interpretation.review.ai.WeeklyReviewAiTestProperties;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.repository.StoreRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WeeklyReviewServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");

    @Test
    void composesExactSnapshotWithItsPublishedEnrichment() {
        UUID storeId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        StoreRepository stores = mock(StoreRepository.class);
        Store store = mock(Store.class);
        when(store.getId()).thenReturn(storeId);
        when(store.getTimezone()).thenReturn("Europe/Moscow");
        when(stores.findById(storeId)).thenReturn(Optional.of(store));

        WeeklyReviewResponse base = mock(WeeklyReviewResponse.class);
        WeeklyReviewResponse enriched = mock(WeeklyReviewResponse.class);
        PersistedWeeklyReviewSnapshot snapshot = new PersistedWeeklyReviewSnapshot(
                snapshotId,
                storeId,
                1,
                null,
                base,
                "a".repeat(64),
                NOW.minusSeconds(60)
        );
        WeeklyReviewSnapshotStore snapshots = mock(WeeklyReviewSnapshotStore.class);
        when(snapshots.findLatest(eq(storeId), any(DateRange.class)))
                .thenReturn(Optional.of(snapshot));

        PersistedWeeklyReviewAiEnrichment enrichment = enrichment(snapshotId);
        WeeklyReviewAiEnrichmentStore enrichments =
                mock(WeeklyReviewAiEnrichmentStore.class);
        when(enrichments.findPublished(snapshotId, NOW))
                .thenReturn(Optional.of(enrichment));
        WeeklyReviewAiEnricher enricher = mock(WeeklyReviewAiEnricher.class);
        when(enricher.apply(
                base,
                enrichment.validationResult(),
                enrichment.publishedAt()
        )).thenReturn(enriched);

        WeeklyReviewService service = new WeeklyReviewService(
                stores,
                mock(WeeklyReviewFactsSource.class),
                snapshots,
                new WeeklyReviewAiReadSupport(
                        enrichments,
                        enricher,
                        mock(WeeklyReviewAiStateResolver.class),
                        WeeklyReviewAiTestProperties.properties(true, false, false)
                ),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        assertThat(service.current(storeId)).containsSame(enriched);
        verify(enrichments).findPublished(snapshotId, NOW);
        verify(enricher).apply(
                base,
                enrichment.validationResult(),
                enrichment.publishedAt()
        );
    }

    @Test
    void disabledFlagReturnsDeterministicSnapshotWithoutReadingEnrichment() {
        UUID storeId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        StoreRepository stores = mock(StoreRepository.class);
        Store store = mock(Store.class);
        when(store.getId()).thenReturn(storeId);
        when(store.getTimezone()).thenReturn("Europe/Moscow");
        when(stores.findById(storeId)).thenReturn(Optional.of(store));
        WeeklyReviewResponse base = mock(WeeklyReviewResponse.class);
        WeeklyReviewSnapshotStore snapshots = mock(WeeklyReviewSnapshotStore.class);
        when(snapshots.findLatest(eq(storeId), any(DateRange.class)))
                .thenReturn(Optional.of(new PersistedWeeklyReviewSnapshot(
                        snapshotId,
                        storeId,
                        1,
                        null,
                        base,
                        "a".repeat(64),
                        NOW
                )));
        WeeklyReviewAiEnrichmentStore enrichments =
                mock(WeeklyReviewAiEnrichmentStore.class);
        when(enrichments.findPublished(snapshotId, NOW))
                .thenReturn(Optional.of(enrichment(snapshotId)));

        WeeklyReviewAiStateResolver stateResolver =
                mock(WeeklyReviewAiStateResolver.class);
        PersistedWeeklyReviewSnapshot persisted = snapshots.findLatest(
                storeId, new WeeklyReviewPolicyV1().period(NOW, "Europe/Moscow").current()
        ).orElseThrow();
        when(stateResolver.apply(persisted, NOW)).thenReturn(base);

        WeeklyReviewService service = new WeeklyReviewService(
                stores,
                mock(WeeklyReviewFactsSource.class),
                snapshots,
                new WeeklyReviewAiReadSupport(
                        enrichments,
                        mock(WeeklyReviewAiEnricher.class),
                        stateResolver,
                        WeeklyReviewAiTestProperties.properties(false, false, false)
                ),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        assertThat(service.current(storeId)).containsSame(base);
        verify(enrichments, never()).findPublished(snapshotId, NOW);
    }

    private PersistedWeeklyReviewAiEnrichment enrichment(UUID snapshotId) {
        WeeklyReviewAiContent content = new WeeklyReviewAiContent(
                4,
                new WeeklyReviewAiContent.Summary(
                        "Итог недели",
                        List.of("STORE.NET_REVENUE")
                ),
                List.of(),
                List.of()
        );
        return new PersistedWeeklyReviewAiEnrichment(
                UUID.randomUUID(),
                snapshotId,
                "weekly-interpretation-v22",
                4,
                "b".repeat(64),
                content,
                "{\"canonical\":true}",
                "c".repeat(64),
                NOW.minusSeconds(10),
                NOW,
                NOW
        );
    }
}
