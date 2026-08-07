package com.storeanalytics.interpretation.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.interpretation.config.InterpretationFeatureProperties;
import com.storeanalytics.interpretation.config.WeeklyInsightAvailabilityProperties;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Facts;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Manifest;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.QualityStatus;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Versions;
import com.storeanalytics.interpretation.generation.LlmAnalysisTriggerType;
import com.storeanalytics.interpretation.query.WeeklyInsightStateRepository.ProcessState;
import com.storeanalytics.interpretation.snapshot.PersistedWeeklySnapshot;
import com.storeanalytics.interpretation.snapshot.WeeklySnapshotPayload;
import com.storeanalytics.interpretation.snapshot.WeeklySnapshotStore;
import com.storeanalytics.interpretation.snapshot.WeeklyAnalyticsFactsQuery;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class WeeklyInsightQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-03T06:00:00Z");
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 7, 27);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 8, 2);

    private final UUID storeId = UUID.randomUUID();
    private final WeeklyInsightStateRepository stateRepository =
            mock(WeeklyInsightStateRepository.class);
    private final WeeklyInterpretationQueryRepository interpretationRepository =
            mock(WeeklyInterpretationQueryRepository.class);
    private final WeeklySnapshotStore snapshotStore = mock(WeeklySnapshotStore.class);
    private final WeeklyInsightPresentationFactory presentationFactory =
            mock(WeeklyInsightPresentationFactory.class);

    @BeforeEach
    void setUp() {
        when(stateRepository.storeTimezone(storeId))
                .thenReturn(Optional.of("Europe/Moscow"));
        when(interpretationRepository.findLatestForPeriod(
                storeId, PERIOD_START, PERIOD_END
        )).thenReturn(Optional.empty());
        when(snapshotStore.findLatest(
                storeId, new StoreKpiPeriod(PERIOD_START, PERIOD_END)
        )).thenReturn(Optional.empty());
        when(stateRepository.latestSnapshotJob(
                storeId, PERIOD_START, PERIOD_END
        )).thenReturn(Optional.empty());
    }

    @Test
    void returnsUnavailableBusinessStateWhenFeatureIsDisabled() {
        WeeklyInsightResponse response = service(false, false, false).current(storeId);

        assertThat(response.state()).isEqualTo(WeeklyInsightState.UNAVAILABLE);
        assertThat(response.reasonCode())
                .isEqualTo(WeeklyInsightReasonCode.PERIOD_NOT_AVAILABLE);
        assertThat(response.period().periodStart()).isEqualTo(PERIOD_START);
        assertThat(response.nextRefreshAt()).isNull();
        assertThat(response.content()).isNull();
    }

    @Test
    void distinguishesNormalPreparationFromDelayedSourceData() {
        ProcessState recent = new ProcessState(
                "RUNNING", NOW.minusSeconds(120), NOW.minusSeconds(10)
        );
        when(stateRepository.latestSnapshotJob(storeId, PERIOD_START, PERIOD_END))
                .thenReturn(Optional.of(recent));

        WeeklyInsightResponse preparing = service(true, true, true).current(storeId);

        assertThat(preparing.state()).isEqualTo(WeeklyInsightState.PREPARING);
        assertThat(preparing.reasonCode())
                .isEqualTo(WeeklyInsightReasonCode.WAITING_FOR_DATA);
        assertThat(preparing.nextRefreshAt()).isEqualTo(NOW.plusSeconds(15));

        ProcessState old = new ProcessState(
                "RUNNING", NOW.minusSeconds(600), NOW.minusSeconds(10)
        );
        when(stateRepository.latestSnapshotJob(storeId, PERIOD_START, PERIOD_END))
                .thenReturn(Optional.of(old));

        WeeklyInsightResponse delayed = service(true, true, true).current(storeId);
        assertThat(delayed.state()).isEqualTo(WeeklyInsightState.DELAYED);
        assertThat(delayed.reasonCode())
                .isEqualTo(WeeklyInsightReasonCode.SOURCE_DELAYED);
    }

    @Test
    void returnsDeterministicFallbackForBlockedSnapshot() {
        PersistedWeeklySnapshot snapshot = snapshot(QualityStatus.BLOCKED);
        WeeklyInsightFallbackView fallback = new WeeklyInsightFallbackView(
                "Результаты недели", "Недостаточно данных", QualityStatus.BLOCKED,
                List.of("SOURCE_DATA_INCOMPLETE")
        );
        when(snapshotStore.findLatest(
                storeId, new StoreKpiPeriod(PERIOD_START, PERIOD_END)
        )).thenReturn(Optional.of(snapshot));
        when(presentationFactory.fallback(snapshot)).thenReturn(fallback);

        WeeklyInsightResponse response = service(true, true, true).current(storeId);

        assertThat(response.state()).isEqualTo(WeeklyInsightState.UNAVAILABLE);
        assertThat(response.reasonCode())
                .isEqualTo(WeeklyInsightReasonCode.DATA_QUALITY_BLOCKED);
        assertThat(response.fallback()).isSameAs(fallback);
        assertThat(response.sourceDataUpdatedAt())
                .isEqualTo(snapshot.sourceDataCutoff());
    }

    @Test
    void keepsPublishedContentAvailableWhileRevisionIsRunning() throws Exception {
        PersistedWeeklySnapshot snapshot = snapshot(QualityStatus.READY);
        WeeklyInterpretationDetailView interpretation = interpretation(snapshot);
        WeeklyInsightContentView content = new WeeklyInsightContentView(
                JsonMapper.builder().build().readTree("{}"),
                JsonMapper.builder().build().readTree("{}"),
                List.of(),
                JsonMapper.builder().build().readTree("[]")
        );
        when(interpretationRepository.findLatestForPeriod(
                storeId, PERIOD_START, PERIOD_END
        )).thenReturn(Optional.of(interpretation));
        when(snapshotStore.findLatest(
                storeId, new StoreKpiPeriod(PERIOD_START, PERIOD_END)
        )).thenReturn(Optional.of(snapshot));
        when(snapshotStore.findById(snapshot.id())).thenReturn(Optional.of(snapshot));
        when(presentationFactory.content(interpretation, snapshot)).thenReturn(content);
        when(stateRepository.latestSnapshotJob(storeId, PERIOD_START, PERIOD_END))
                .thenReturn(Optional.of(new ProcessState(
                        "RUNNING", NOW.minusSeconds(60), NOW.minusSeconds(5)
                )));

        WeeklyInsightResponse response = service(true, true, true).current(storeId);

        assertThat(response.state()).isEqualTo(WeeklyInsightState.READY);
        assertThat(response.revisionState())
                .isEqualTo(WeeklyInsightRevisionState.UPDATING);
        assertThat(response.content()).isSameAs(content);
        assertThat(response.interpretationId())
                .isEqualTo(interpretation.interpretation().id());
        assertThat(response.nextRefreshAt()).isEqualTo(NOW.plusSeconds(15));
    }

    private WeeklyInsightQueryService service(
            boolean snapshotEnabled,
            boolean generationEnabled,
            boolean publicationEnabled
    ) {
        return new WeeklyInsightQueryService(
                stateRepository,
                interpretationRepository,
                snapshotStore,
                presentationFactory,
                new InterpretationFeatureProperties(
                        snapshotEnabled, generationEnabled, publicationEnabled
                ),
                new WeeklyInsightAvailabilityProperties(
                        Duration.ofMinutes(5), Duration.ofSeconds(15),
                        LocalTime.of(8, 0)
                ),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private PersistedWeeklySnapshot snapshot(QualityStatus qualityStatus) {
        WeeklyAnalyticsFactsQuery query = new WeeklyAnalyticsFactsQuery(
                storeId,
                new StoreKpiPeriod(PERIOD_START, PERIOD_END),
                new StoreKpiPeriod(
                        PERIOD_START.minusWeeks(1), PERIOD_END.minusWeeks(1)
                )
        );
        return new PersistedWeeklySnapshot(
                UUID.randomUUID(),
                storeId,
                query,
                "Europe/Moscow",
                1,
                null,
                "INITIAL",
                null,
                UUID.randomUUID(),
                NOW.minusSeconds(120),
                NOW.minusSeconds(120),
                qualityStatus,
                new Versions(1, "metrics-v1", "calculation-v1", "quality-v1"),
                new WeeklySnapshotPayload(
                        1,
                        new Manifest(
                                List.of(), List.of(), List.of(), List.of(),
                                List.of(), List.of()
                        ),
                        new Facts(List.of(), List.of(), List.of(), List.of())
                ),
                "a".repeat(64),
                List.of(),
                NOW.minusSeconds(120)
        );
    }

    private WeeklyInterpretationDetailView interpretation(
            PersistedWeeklySnapshot snapshot
    ) throws Exception {
        WeeklyInterpretationSummaryView summary = new WeeklyInterpretationSummaryView(
                UUID.randomUUID(),
                storeId,
                snapshot.id(),
                PERIOD_START,
                PERIOD_END,
                "Europe/Moscow",
                1,
                1,
                true,
                null,
                LlmAnalysisTriggerType.INITIAL,
                "b".repeat(64),
                1,
                QualityStatus.READY,
                0,
                NOW.minusSeconds(30),
                NOW.minusSeconds(20)
        );
        return new WeeklyInterpretationDetailView(
                summary,
                JsonMapper.builder().build().readTree("{}"),
                List.of()
        );
    }
}
