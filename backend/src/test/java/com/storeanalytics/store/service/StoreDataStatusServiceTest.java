package com.storeanalytics.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.store.repository.StoreDataStatusRepository;
import com.storeanalytics.store.repository.StoreDataStatusSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StoreDataStatusServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-22T08:00:00Z");
    private static final ZoneId STORE_ZONE = ZoneId.of("Europe/Kaliningrad");

    private StoreDataStatusRepository repository;
    private StoreDataStatusService service;

    @BeforeEach
    void setUp() {
        repository = mock(StoreDataStatusRepository.class);
        service = new StoreDataStatusService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void marksStoreCurrentUsingTheOlderOfSalesAndReturnsCoverage() {
        UUID storeId = UUID.randomUUID();
        Instant salesCompletedAt = Instant.parse("2026-07-22T05:00:00Z");
        Instant returnsCompletedAt = Instant.parse("2026-07-22T06:00:00Z");
        when(repository.findByStoreId(storeId)).thenReturn(Optional.of(snapshot(
                storeId,
                exclusiveStartOf(LocalDate.of(2026, 7, 22)),
                salesCompletedAt,
                exclusiveStartOf(LocalDate.of(2026, 7, 23)),
                returnsCompletedAt,
                null,
                null
        )));

        StoreDataStatusView result = service.get(storeId);

        assertThat(result.status()).isEqualTo(StoreDataFreshnessStatus.CURRENT);
        assertThat(result.expectedThroughDate()).isEqualTo(LocalDate.of(2026, 7, 21));
        assertThat(result.salesDataThroughDate()).isEqualTo(LocalDate.of(2026, 7, 21));
        assertThat(result.returnsDataThroughDate()).isEqualTo(LocalDate.of(2026, 7, 22));
        assertThat(result.dataThroughDate()).isEqualTo(LocalDate.of(2026, 7, 21));
        assertThat(result.lagDays()).isZero();
        assertThat(result.lastCompletedSyncAt()).isEqualTo(returnsCompletedAt);
        assertThat(result.openQualityIssueCount()).isZero();
        assertThat(result.synchronization().active()).isFalse();
        assertThat(result.checkedAt()).isEqualTo(NOW);
    }

    @Test
    void marksStoreStaleAndReportsWholeLagDays() {
        UUID storeId = UUID.randomUUID();
        Instant coverageEnd = exclusiveStartOf(LocalDate.of(2026, 7, 20));
        when(repository.findByStoreId(storeId)).thenReturn(Optional.of(snapshot(
                storeId, coverageEnd, NOW.minusSeconds(120), coverageEnd, NOW.minusSeconds(60),
                null, null
        )));

        StoreDataStatusView result = service.get(storeId);

        assertThat(result.status()).isEqualTo(StoreDataFreshnessStatus.STALE);
        assertThat(result.dataThroughDate()).isEqualTo(LocalDate.of(2026, 7, 19));
        assertThat(result.lagDays()).isEqualTo(2);
    }

    @Test
    void requiresCoverageForBothSalesAndReturns() {
        UUID storeId = UUID.randomUUID();
        when(repository.findByStoreId(storeId)).thenReturn(Optional.of(snapshot(
                storeId, exclusiveStartOf(LocalDate.of(2026, 7, 22)), NOW,
                null, null, null, null
        )));

        StoreDataStatusView result = service.get(storeId);

        assertThat(result.status()).isEqualTo(StoreDataFreshnessStatus.NOT_SYNCED);
        assertThat(result.dataThroughDate()).isNull();
        assertThat(result.lagDays()).isNull();
    }

    @Test
    void activeSynchronizationHasPriorityOverPreviousFailure() {
        UUID storeId = UUID.randomUUID();
        UUID syncId = UUID.randomUUID();
        when(repository.findByStoreId(storeId)).thenReturn(Optional.of(snapshot(
                storeId, null, null, null, null, syncId, "FAILED"
        )));

        StoreDataStatusView result = service.get(storeId);

        assertThat(result.status()).isEqualTo(StoreDataFreshnessStatus.SYNCING);
        assertThat(result.synchronization().active()).isTrue();
        assertThat(result.synchronization().id()).isEqualTo(syncId);
        assertThat(result.synchronization().type()).isEqualTo(StoreSyncActivityType.JOB);
        assertThat(result.synchronization().status()).isEqualTo("RUNNING");
        assertThat(result.synchronization().phase()).isEqualTo("SALES");
    }

    @Test
    void latestFailedTerminalActivityProducesErrorStatus() {
        UUID storeId = UUID.randomUUID();
        Instant failedAt = Instant.parse("2026-07-22T07:30:00Z");
        when(repository.findByStoreId(storeId)).thenReturn(Optional.of(new StoreDataStatusSnapshot(
                storeId,
                STORE_ZONE.getId(),
                exclusiveStartOf(LocalDate.of(2026, 7, 22)),
                NOW.minusSeconds(600),
                exclusiveStartOf(LocalDate.of(2026, 7, 22)),
                NOW.minusSeconds(500),
                null, null, null, null, null, null,
                "FAILED", failedAt, "Sales synchronization failed: TimeoutException", failedAt, 1
        )));

        StoreDataStatusView result = service.get(storeId);

        assertThat(result.status()).isEqualTo(StoreDataFreshnessStatus.ERROR);
        assertThat(result.lastError()).isEqualTo("Sales synchronization failed: TimeoutException");
        assertThat(result.lastErrorAt()).isEqualTo(failedAt);
    }

    private StoreDataStatusSnapshot snapshot(
            UUID storeId,
            Instant salesThrough,
            Instant salesCompletedAt,
            Instant returnsThrough,
            Instant returnsCompletedAt,
            UUID activeSyncId,
            String latestTerminalStatus
    ) {
        boolean active = activeSyncId != null;
        return new StoreDataStatusSnapshot(
                storeId,
                STORE_ZONE.getId(),
                salesThrough,
                salesCompletedAt,
                returnsThrough,
                returnsCompletedAt,
                activeSyncId,
                active ? "JOB" : null,
                active ? "RUNNING" : null,
                active ? "SALES" : null,
                active ? NOW.minusSeconds(30) : null,
                null,
                latestTerminalStatus,
                latestTerminalStatus == null ? null : NOW.minusSeconds(120),
                latestTerminalStatus == null ? null : "Previous synchronization failed",
                latestTerminalStatus == null ? null : NOW.minusSeconds(120),
                0
        );
    }

    private Instant exclusiveStartOf(LocalDate date) {
        return date.atStartOfDay(STORE_ZONE).toInstant();
    }
}
