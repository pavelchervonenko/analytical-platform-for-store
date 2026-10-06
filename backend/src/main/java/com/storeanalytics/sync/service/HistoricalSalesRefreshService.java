package com.storeanalytics.sync.service;

import com.storeanalytics.common.config.HistoricalSalesRefreshProperties;
import com.storeanalytics.common.config.SyncProperties;
import com.storeanalytics.integration.connection.model.IntegrationConnection;
import com.storeanalytics.integration.connection.repository.IntegrationConnectionRepository;
import com.storeanalytics.integration.livesklad.client.HistoricalSalesReadScope;
import com.storeanalytics.sync.exception.HistoricalSalesReadBudgetException;
import com.storeanalytics.sync.exception.HistoricalSalesDependencyException;
import com.storeanalytics.sync.model.SourceSystem;
import com.storeanalytics.sync.model.SyncJob;
import com.storeanalytics.sync.model.SyncJobDefinition;
import com.storeanalytics.sync.model.SyncJobPhase;
import com.storeanalytics.sync.model.SyncJobStatus;
import com.storeanalytics.sync.model.SyncJobType;
import com.storeanalytics.sync.repository.HistoricalSalesRefreshStore;
import com.storeanalytics.sync.repository.HistoricalSalesRefreshStore.State;
import com.storeanalytics.sync.repository.SyncJobRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HistoricalSalesRefreshService {
    private static final Logger LOGGER = LoggerFactory.getLogger(HistoricalSalesRefreshService.class);
    private static final Set<SyncJobStatus> ACTIVE = Set.of(
            SyncJobStatus.PENDING, SyncJobStatus.RUNNING, SyncJobStatus.WAITING_RETRY);
    private final HistoricalSalesRefreshProperties properties;
    private final SyncProperties sync;
    private final Clock clock;
    private final IntegrationConnectionRepository connections;
    private final SyncJobRepository jobs;
    private final HistoricalSalesRefreshStore states;
    private final SyncClassificationReadinessService readiness;
    private final HistoricalSalesDependencyGuard dependencies;

    public HistoricalSalesRefreshService(HistoricalSalesRefreshProperties properties, SyncProperties sync,
                                        Clock clock, IntegrationConnectionRepository connections,
                                        SyncJobRepository jobs, HistoricalSalesRefreshStore states,
                                        Readiness readiness) {
        this.properties = properties;
        this.sync = sync;
        this.clock = clock;
        this.connections = connections;
        this.jobs = jobs;
        this.states = states;
        this.readiness = readiness == null ? null : readiness.classification();
        this.dependencies = readiness == null ? null : readiness.dependencies();
    }

    @Transactional
    public Optional<SyncJobView> enqueue() {
        if (!properties.enabled()) {
            return Optional.empty();
        }
        IntegrationConnection connection = connections
                .findByConnectionKeyAndActiveTrue("livesklad-default")
                .filter(value -> value.getSourceSystem() == SourceSystem.LIVESKLAD).orElseThrow();
        // Use the same lock order as publication/budget/completion: job, then sweep state.
        if (!jobs.findActiveForUpdate(connection.getId(), ACTIVE).isEmpty()) {
            return Optional.empty();
        }
        LocalDate today = LocalDate.now(clock.withZone(sync.reportingZone()));
        Instant routineEnd = today.atStartOfDay(sync.reportingZone()).toInstant();
        Instant routineStart = today.minusDays(sync.incrementalOverlapDays())
                .atStartOfDay(sync.reportingZone()).toInstant();
        if (jobs.findFirstByConnectionIdAndJobTypeAndPeriodStartAndPeriodEndOrderByCreatedAtDesc(
                connection.getId(), SyncJobType.INCREMENTAL, routineStart, routineEnd)
                .filter(value -> value.getStatus() == SyncJobStatus.SUCCESS).isEmpty()) {
            return Optional.empty();
        }
        Instant start = properties.startDate().atStartOfDay(sync.reportingZone()).toInstant();
        if (!start.isBefore(routineStart)) {
            return Optional.empty();
        }
        states.initialize(connection.getId(), start, routineStart, properties.windowMinutes(), today, clock.instant());
        State observed = states.find(connection.getId(), false).orElseThrow();
        SyncJob previous = observed.activeJobId() == null ? null
                : jobs.findByIdForUpdate(observed.activeJobId()).orElse(null);
        State state = states.find(connection.getId(), true).orElseThrow();
        if (!Objects.equals(state.activeJobId(), observed.activeJobId())) {
            return Optional.empty();
        }
        if (!state.historyStart().equals(start)) {
            throw new IllegalStateException("Historical start changed; explicit state review is required");
        }
        if (state.blockedAt() != null) {
            if (!state.backfillRepairAllowed()) {
                LOGGER.warn("Historical SALE sweep requires explicit review of an unsupported linked RETURN source");
                return Optional.empty();
            }
            var repaired = jobs.findCoveringManualBackfill(connection.getId(), state.repairStart(),
                    state.repairEnd(), state.blockedAt(), PageRequest.of(0, 1));
            if (repaired.isEmpty()) {
                LOGGER.warn("Historical SALE sweep remains blocked after a permanent failure");
                return Optional.empty();
            }
            // Publishers acquire job then connection; this scheduler holds job/state before connection.
            // No publisher acquires sweep state while holding its connection publication transaction.
            dependencies.lockConnection(connection.getId());
            if (state.repairDependenciesRequired()
                    && !dependencies.repairSatisfied(connection.getId(), repaired.getFirst().getId(),
                    state.blockedAt(), state.linkedReturns())) {
                LOGGER.warn("Historical SALE sweep awaits accepted dependent RETURN repair");
                return Optional.empty();
            }
            advance(state, state.blockedEnd(), repaired.getFirst());
            previous = null;
            state = states.find(connection.getId(), true).orElseThrow();
        }
        if (previous != null) {
            if (previous.getStatus() == SyncJobStatus.CANCELLED) {
                states.release(connection.getId(), windowMinutes(previous));
            } else {
                throw new IllegalStateException("Historical job state is inconsistent with its cursor");
            }
            state = states.find(connection.getId(), true).orElseThrow();
        } else if (state.activeJobId() != null) {
            // Retention may remove a terminal job. Never infer success or skip its interval.
            states.release(connection.getId(), state.preferredWindowMinutes());
            state = states.find(connection.getId(), true).orElseThrow();
        }
        Instant now = clock.instant();
        if (!state.cursorStart().isBefore(state.cycleEnd())) {
            if (state.nextCycleAt() != null && now.isBefore(state.nextCycleAt())) {
                return Optional.empty();
            }
            states.beginCycle(connection.getId(), routineStart, now);
            state = states.find(connection.getId(), true).orElseThrow();
        }
        if (state.budgetDay().equals(today) && state.requestAttempts() >= properties.maxRequestsPerDay()) {
            return Optional.empty();
        }
        if (!readiness.inspect(connection, state.cursorStart()).ready()) {
            states.classificationBlocked(connection.getId(), true);
            return Optional.empty();
        }
        states.classificationBlocked(connection.getId(), false);
        int minutes = Math.min(properties.windowMinutes(), state.preferredWindowMinutes());
        Instant end = state.cursorStart().plus(Duration.ofMinutes(minutes));
        if (end.isAfter(state.cycleEnd())) {
            end = state.cycleEnd();
        }
        SyncJob job = jobs.saveAndFlush(SyncJob.create(new SyncJobDefinition(
                connection, null, SyncJobType.HISTORICAL_SALES, state.cursorStart(), end,
                Duration.ofMinutes(minutes), sync.maxAttempts()), now));
        states.attach(connection.getId(), job.getId());
        LOGGER.info("Created bounded historical SALE job {} for {}..{}", job.getId(), job.getPeriodStart(), end);
        return Optional.of(SyncJobView.from(job));
    }

    /** Called while coordinator holds this exact job lock; cursor commits with SUCCESS. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(SyncJob job) {
        if (job.getJobType() != SyncJobType.HISTORICAL_SALES || job.getStatus() != SyncJobStatus.SUCCESS) {
            return;
        }
        State state = states.find(job.getConnection().getId(), true).orElseThrow();
        if (!job.getId().equals(state.activeJobId()) || !job.getPeriodStart().equals(state.cursorStart())) {
            throw new IllegalStateException("Historical success does not match the durable cursor");
        }
        advance(state, job.getPeriodEnd(), job);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordOutcome(SyncJob job) {
        if (job.getJobType() != SyncJobType.HISTORICAL_SALES) {
            return;
        }
        if (job.getStatus() == SyncJobStatus.SUCCESS) {
            complete(job);
        } else if (job.getStatus() == SyncJobStatus.FAILED) {
            State state = states.find(job.getConnection().getId(), true).orElseThrow();
            if (!job.getId().equals(state.activeJobId()) || !job.getPeriodStart().equals(state.cursorStart())) {
                throw new IllegalStateException("Historical failure does not match durable cursor");
            }
            states.block(state.connectionId(), job.getPeriodStart(), job.getPeriodEnd(), job.getFinishedAt());
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordDependencyFailure(SyncJob job, HistoricalSalesDependencyException failure) {
        recordOutcome(job);
        states.repairRange(job.getConnection().getId(), failure.repairStart(), failure.repairEnd(),
                failure.backfillRepairAllowed(), failure.linkedReturns());
    }

    private void advance(State state, Instant end, SyncJob job) {
        if (end.isAfter(state.cycleEnd()) || !end.isAfter(state.cursorStart())) {
            throw new IllegalStateException("Historical success exceeds the selected cycle");
        }
        Instant now = clock.instant();
        states.advance(state, end, now, now.plus(properties.cycleInterval()),
                Math.min(properties.windowMinutes(), windowMinutes(job) * 2));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void chargeRequest(HistoricalSalesReadScope.Context context) {
        SyncJob job = jobs.findByIdForUpdate(context.jobId()).orElseThrow();
        validateLease(job, context, clock.instant());
        State state = states.find(job.getConnection().getId(), true).orElseThrow();
        if (!job.getId().equals(state.activeJobId())) {
            throw new IllegalStateException("Historical request has no active durable cursor");
        }
        LocalDate day = LocalDate.now(clock.withZone(sync.reportingZone()));
        if (state.budgetDay().equals(day) && state.requestAttempts() >= properties.maxRequestsPerDay()) {
            Instant reset = day.plusDays(1).atStartOfDay(sync.reportingZone()).toInstant();
            throw new HistoricalSalesReadBudgetException(Duration.between(clock.instant(), reset).plusSeconds(1), true);
        }
        states.charge(state.connectionId(), day);
    }

    public static void validateLease(SyncJob job, HistoricalSalesReadScope.Context context, Instant now) {
        if (!context.jobId().equals(job.getId())
                || job.getJobType() != SyncJobType.HISTORICAL_SALES || job.getStatus() != SyncJobStatus.RUNNING
                || job.getPhase() != SyncJobPhase.SALES || job.isCancelRequested()
                || !context.leaseOwner().equals(job.getLeaseOwner()) || job.getLeaseUntil() == null
                || !now.isBefore(job.getLeaseUntil()) || job.getAttemptCount() != context.attempt()
                || !job.getCursorStart().equals(context.start()) || !job.getCurrentWindowEnd().equals(context.end())) {
            throw new IllegalStateException("Historical SALE execution lost its exact lease or window");
        }
    }

    public int maxRequestsPerStep() {
        return properties.maxRequestsPerStep();
    }

    private static int windowMinutes(SyncJob job) {
        return Math.max(15, Math.toIntExact(Duration.between(job.getPeriodStart(), job.getPeriodEnd()).toMinutes()));
    }
    /** Classification admission and dependent-fact repair are distinct readiness checks. */
    @Component
    public record Readiness(SyncClassificationReadinessService classification,
                            HistoricalSalesDependencyGuard dependencies) { }

}
