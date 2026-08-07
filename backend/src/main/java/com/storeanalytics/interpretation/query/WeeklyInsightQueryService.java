package com.storeanalytics.interpretation.query;

import com.storeanalytics.interpretation.config.InterpretationFeatureProperties;
import com.storeanalytics.interpretation.config.WeeklyInsightAvailabilityProperties;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.QualityStatus;
import com.storeanalytics.interpretation.query.WeeklyInsightStateRepository.ProcessState;
import com.storeanalytics.interpretation.snapshot.PersistedWeeklySnapshot;
import com.storeanalytics.interpretation.snapshot.WeeklySnapshotStore;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WeeklyInsightQueryService {

    private final WeeklyInsightStateRepository stateRepository;
    private final WeeklyInterpretationQueryRepository interpretationRepository;
    private final WeeklySnapshotStore snapshotStore;
    private final WeeklyInsightPresentationFactory presentationFactory;
    private final InterpretationFeatureProperties featureProperties;
    private final WeeklyInsightAvailabilityProperties availabilityProperties;
    private final Clock clock;

    public WeeklyInsightQueryService(
            WeeklyInsightStateRepository stateRepository,
            WeeklyInterpretationQueryRepository interpretationRepository,
            WeeklySnapshotStore snapshotStore,
            WeeklyInsightPresentationFactory presentationFactory,
            InterpretationFeatureProperties featureProperties,
            WeeklyInsightAvailabilityProperties availabilityProperties,
            Clock clock
    ) {
        this.stateRepository = stateRepository;
        this.interpretationRepository = interpretationRepository;
        this.snapshotStore = snapshotStore;
        this.presentationFactory = presentationFactory;
        this.featureProperties = featureProperties;
        this.availabilityProperties = availabilityProperties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public WeeklyInsightResponse current(UUID storeId) {
        Instant now = clock.instant();
        String timezone = stateRepository.storeTimezone(storeId)
                .orElseThrow(() -> new IllegalStateException(
                        "Accessible store timezone is unavailable"
                ));
        ZoneId zone = ZoneId.of(timezone);
        LocalDate currentWeekStart = LocalDate.ofInstant(now, zone)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        StoreKpiPeriod period = new StoreKpiPeriod(
                currentWeekStart.minusWeeks(1),
                currentWeekStart.minusDays(1)
        );
        WeeklyInsightPeriodView periodView = new WeeklyInsightPeriodView(
                period.start(), period.end(), timezone
        );

        Optional<WeeklyInterpretationDetailView> interpretation =
                interpretationRepository.findLatestForPeriod(
                        storeId, period.start(), period.end()
                );
        Optional<PersistedWeeklySnapshot> latestSnapshot =
                snapshotStore.findLatest(storeId, period);
        Optional<ProcessState> snapshotJob = stateRepository.latestSnapshotJob(
                storeId, period.start(), period.end()
        );
        if (interpretation.isPresent()) {
            return ready(
                    periodView,
                    interpretation.get(),
                    latestSnapshot,
                    snapshotJob,
                    now
            );
        }
        return unavailableOrPreparing(
                periodView,
                latestSnapshot,
                snapshotJob,
                now
        );
    }

    private WeeklyInsightResponse ready(
            WeeklyInsightPeriodView period,
            WeeklyInterpretationDetailView interpretation,
            Optional<PersistedWeeklySnapshot> latestSnapshot,
            Optional<ProcessState> snapshotJob,
            Instant now
    ) {
        WeeklyInterpretationSummaryView summary = interpretation.interpretation();
        PersistedWeeklySnapshot publishedSnapshot = snapshotStore
                .findById(summary.snapshotId())
                .orElseThrow(() -> new IllegalStateException(
                        "Published interpretation snapshot is unavailable"
                ));
        RevisionStatus revision = revisionStatus(
                summary,
                latestSnapshot,
                snapshotJob,
                now
        );
        return new WeeklyInsightResponse(
                period,
                WeeklyInsightState.READY,
                WeeklyInsightReasonCode.READY,
                revision.state() == WeeklyInsightRevisionState.CURRENT
                        ? "Недельная интерпретация готова."
                        : "Недельная интерпретация готова и обновляется.",
                revision.updatedAt(),
                revision.nextRefreshAt(),
                summary.id(),
                summary.interpretationRevision(),
                summary.publishedAt(),
                publishedSnapshot.sourceDataCutoff(),
                revision.state(),
                presentationFactory.content(interpretation, publishedSnapshot),
                null
        );
    }

    private RevisionStatus revisionStatus(
            WeeklyInterpretationSummaryView published,
            Optional<PersistedWeeklySnapshot> latestSnapshot,
            Optional<ProcessState> snapshotJob,
            Instant now
    ) {
        Optional<ProcessState> activeSnapshotJob = snapshotJob
                .filter(ProcessState::active)
                .filter(job -> job.updatedAt().isAfter(published.publishedAt()));
        if (activeSnapshotJob.isPresent()) {
            return updating(activeSnapshotJob.get(), now);
        }
        if (latestSnapshot.isEmpty()
                || latestSnapshot.get().id().equals(published.snapshotId())) {
            return new RevisionStatus(
                    WeeklyInsightRevisionState.CURRENT,
                    published.publishedAt(),
                    null
            );
        }
        Optional<ProcessState> analysisJob = stateRepository.latestAnalysisJob(
                latestSnapshot.get().id()
        );
        if (analysisJob.isPresent() && analysisJob.get().active()) {
            return updating(analysisJob.get(), now);
        }
        Instant updatedAt = analysisJob.map(ProcessState::updatedAt)
                .orElse(latestSnapshot.get().createdAt());
        return new RevisionStatus(
                WeeklyInsightRevisionState.UPDATE_DELAYED,
                updatedAt,
                null
        );
    }

    private RevisionStatus updating(ProcessState process, Instant now) {
        boolean delayed = delayed(process.createdAt(), now);
        return new RevisionStatus(
                delayed
                        ? WeeklyInsightRevisionState.UPDATE_DELAYED
                        : WeeklyInsightRevisionState.UPDATING,
                process.updatedAt(),
                now.plus(availabilityProperties.refreshInterval())
        );
    }

    private WeeklyInsightResponse unavailableOrPreparing(
            WeeklyInsightPeriodView period,
            Optional<PersistedWeeklySnapshot> snapshot,
            Optional<ProcessState> snapshotJob,
            Instant now
    ) {
        if (snapshot.isEmpty()) {
            return withoutSnapshot(period, snapshotJob, now);
        }
        PersistedWeeklySnapshot value = snapshot.get();
        if (value.qualityStatus() == QualityStatus.BLOCKED) {
            return terminal(
                    period,
                    WeeklyInsightReasonCode.DATA_QUALITY_BLOCKED,
                    "Недостаточно качественных данных для интерпретации.",
                    value,
                    value.createdAt()
            );
        }
        if (!featureProperties.generationEnabled()
                || !featureProperties.publicationEnabled()) {
            return terminal(
                    period,
                    WeeklyInsightReasonCode.PERIOD_NOT_AVAILABLE,
                    "Автоматическая интерпретация пока недоступна.",
                    value,
                    value.createdAt()
            );
        }
        Optional<ProcessState> analysisJob = stateRepository.latestAnalysisJob(value.id());
        if (analysisJob.isEmpty()) {
            return analysisInProgress(period, value, value.createdAt(), value.createdAt(), now);
        }
        ProcessState process = analysisJob.get();
        if (process.active()) {
            return analysisInProgress(
                    period, value, process.createdAt(), process.updatedAt(), now
            );
        }
        return terminal(
                period,
                WeeklyInsightReasonCode.ANALYSIS_TEMPORARILY_UNAVAILABLE,
                "Не удалось подготовить интерпретацию. Показатели доступны в кабинете.",
                value,
                process.updatedAt()
        );
    }

    private WeeklyInsightResponse withoutSnapshot(
            WeeklyInsightPeriodView period,
            Optional<ProcessState> snapshotJob,
            Instant now
    ) {
        if (!featureProperties.snapshotEnabled()) {
            return response(
                    period,
                    WeeklyInsightState.UNAVAILABLE,
                    WeeklyInsightReasonCode.PERIOD_NOT_AVAILABLE,
                    "Интерпретация за эту неделю пока недоступна.",
                    now,
                    null,
                    null
            );
        }
        Instant startedAt = snapshotJob.map(ProcessState::createdAt)
                .orElse(period.periodEnd().plusDays(1).atTime(
                        availabilityProperties.targetReadyTime()
                ).atZone(ZoneId.of(period.timezone())).toInstant().minus(
                        availabilityProperties.preparationSla()
                ));
        Instant updatedAt = snapshotJob.map(ProcessState::updatedAt).orElse(startedAt);
        boolean delayed = delayed(startedAt, now)
                || snapshotJob.filter(ProcessState::failed).isPresent();
        return response(
                period,
                delayed ? WeeklyInsightState.DELAYED : WeeklyInsightState.PREPARING,
                delayed
                        ? WeeklyInsightReasonCode.SOURCE_DELAYED
                        : WeeklyInsightReasonCode.WAITING_FOR_DATA,
                delayed
                        ? "Данные за неделю обновляются дольше обычного."
                        : "Ожидаем завершения подготовки данных за неделю.",
                updatedAt,
                now.plus(availabilityProperties.refreshInterval()),
                null
        );
    }

    private WeeklyInsightResponse analysisInProgress(
            WeeklyInsightPeriodView period,
            PersistedWeeklySnapshot snapshot,
            Instant startedAt,
            Instant updatedAt,
            Instant now
    ) {
        boolean delayed = delayed(startedAt, now);
        return response(
                period,
                delayed ? WeeklyInsightState.DELAYED : WeeklyInsightState.PREPARING,
                delayed
                        ? WeeklyInsightReasonCode.ANALYSIS_DELAYED
                        : WeeklyInsightReasonCode.ANALYSIS_IN_PROGRESS,
                delayed
                        ? "Интерпретация готовится дольше обычного. Показатели уже доступны."
                        : "Анализируем результаты недели.",
                updatedAt,
                now.plus(availabilityProperties.refreshInterval()),
                snapshot
        );
    }

    private WeeklyInsightResponse terminal(
            WeeklyInsightPeriodView period,
            WeeklyInsightReasonCode reason,
            String message,
            PersistedWeeklySnapshot snapshot,
            Instant updatedAt
    ) {
        return response(
                period,
                WeeklyInsightState.UNAVAILABLE,
                reason,
                message,
                updatedAt,
                null,
                snapshot
        );
    }

    private WeeklyInsightResponse response(
            WeeklyInsightPeriodView period,
            WeeklyInsightState state,
            WeeklyInsightReasonCode reason,
            String message,
            Instant updatedAt,
            Instant nextRefreshAt,
            PersistedWeeklySnapshot snapshot
    ) {
        return new WeeklyInsightResponse(
                period,
                state,
                reason,
                message,
                updatedAt,
                nextRefreshAt,
                null,
                null,
                null,
                snapshot == null ? null : snapshot.sourceDataCutoff(),
                null,
                null,
                state == WeeklyInsightState.UNAVAILABLE && snapshot != null
                        ? presentationFactory.fallback(snapshot)
                        : null
        );
    }

    private boolean delayed(Instant startedAt, Instant now) {
        return !now.isBefore(startedAt.plus(availabilityProperties.preparationSla()));
    }

    private record RevisionStatus(
            WeeklyInsightRevisionState state,
            Instant updatedAt,
            Instant nextRefreshAt
    ) {
    }
}
