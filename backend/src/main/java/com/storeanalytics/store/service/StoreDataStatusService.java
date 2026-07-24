package com.storeanalytics.store.service;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.metrics.exception.StoreNotFoundException;
import com.storeanalytics.store.repository.StoreDataStatusRepository;
import com.storeanalytics.store.repository.StoreDataStatusSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StoreDataStatusService {

    private final StoreDataStatusRepository statusRepository;
    private final Clock clock;

    public StoreDataStatusService(StoreDataStatusRepository statusRepository, Clock clock) {
        this.statusRepository = statusRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public StoreDataStatusView get(UUID storeId) {
        UUID validatedStoreId = requireNonNull(storeId, "storeId");
        StoreDataStatusSnapshot snapshot = statusRepository.findByStoreId(validatedStoreId)
                .orElseThrow(() -> new StoreNotFoundException(validatedStoreId));
        ZoneId zone = ZoneId.of(snapshot.timezone());
        Instant checkedAt = clock.instant();
        LocalDate expectedThroughDate = LocalDate.now(clock.withZone(zone)).minusDays(1);
        LocalDate salesThrough = completedDate(snapshot.salesThroughExclusive(), zone);
        LocalDate returnsThrough = completedDate(snapshot.returnsThroughExclusive(), zone);
        LocalDate dataThrough = minimum(salesThrough, returnsThrough);
        Integer lagDays = lagDays(dataThrough, expectedThroughDate);
        StoreSyncActivityView synchronization = synchronization(snapshot);

        return new StoreDataStatusView(
                snapshot.storeId(),
                freshness(snapshot, synchronization.active(), dataThrough, expectedThroughDate),
                expectedThroughDate,
                dataThrough,
                salesThrough,
                returnsThrough,
                lagDays,
                latest(snapshot.salesCompletedAt(), snapshot.returnsCompletedAt()),
                synchronization,
                snapshot.openQualityIssueCount(),
                snapshot.lastError(),
                snapshot.lastErrorAt(),
                checkedAt
        );
    }

    private LocalDate completedDate(Instant exclusiveEnd, ZoneId zone) {
        return exclusiveEnd == null
                ? null
                : exclusiveEnd.minusNanos(1).atZone(zone).toLocalDate();
    }

    private LocalDate minimum(LocalDate first, LocalDate second) {
        if (first == null || second == null) {
            return null;
        }
        return first.isBefore(second) ? first : second;
    }

    private Integer lagDays(LocalDate dataThrough, LocalDate expectedThrough) {
        if (dataThrough == null) {
            return null;
        }
        return Math.toIntExact(Math.max(0, ChronoUnit.DAYS.between(
                dataThrough,
                expectedThrough
        )));
    }

    private Instant latest(Instant first, Instant second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.isAfter(second) ? first : second;
    }

    private StoreSyncActivityView synchronization(StoreDataStatusSnapshot snapshot) {
        boolean active = snapshot.activeSyncId() != null;
        return new StoreSyncActivityView(
                active,
                snapshot.activeSyncId(),
                active ? StoreSyncActivityType.valueOf(snapshot.activeSyncType()) : null,
                snapshot.activeSyncStatus(),
                snapshot.activeSyncPhase(),
                snapshot.activeSyncStartedAt(),
                snapshot.activeSyncNextAttemptAt()
        );
    }

    private StoreDataFreshnessStatus freshness(
            StoreDataStatusSnapshot snapshot,
            boolean synchronizationActive,
            LocalDate dataThrough,
            LocalDate expectedThrough
    ) {
        if (synchronizationActive) {
            return StoreDataFreshnessStatus.SYNCING;
        }
        if ("FAILED".equals(snapshot.latestTerminalStatus())) {
            return StoreDataFreshnessStatus.ERROR;
        }
        if (dataThrough == null) {
            return StoreDataFreshnessStatus.NOT_SYNCED;
        }
        return dataThrough.isBefore(expectedThrough)
                ? StoreDataFreshnessStatus.STALE
                : StoreDataFreshnessStatus.CURRENT;
    }
}
