package com.storeanalytics.quality.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.metrics.exception.StoreNotFoundException;
import com.storeanalytics.quality.model.DataQualityIssue;
import com.storeanalytics.quality.model.DataQualitySeverity;
import com.storeanalytics.quality.model.DataQualityStatus;
import com.storeanalytics.quality.repository.DataQualityIssueRepository;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.repository.StoreRepository;
import com.storeanalytics.store.service.StoreCatalogService;
import com.storeanalytics.store.service.StoreDataFreshnessStatus;
import com.storeanalytics.store.service.StoreDataStatusService;
import com.storeanalytics.store.service.StoreDataStatusView;
import com.storeanalytics.store.service.StoreSummaryView;
import com.storeanalytics.store.service.StoreSyncActivityView;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DataQualityServiceTest {

    private static final Instant CHECKED_AT = Instant.parse("2026-07-22T10:00:00Z");

    private StoreCatalogService storeCatalogService;
    private StoreDataStatusService dataStatusService;
    private StoreRepository storeRepository;
    private DataQualityIssueRepository issueRepository;
    private DataQualityService service;

    @BeforeEach
    void setUp() {
        storeCatalogService = mock(StoreCatalogService.class);
        dataStatusService = mock(StoreDataStatusService.class);
        storeRepository = mock(StoreRepository.class);
        issueRepository = mock(DataQualityIssueRepository.class);
        service = new DataQualityService(
                storeCatalogService,
                dataStatusService,
                storeRepository,
                issueRepository,
                Clock.fixed(CHECKED_AT, ZoneOffset.UTC)
        );
    }

    @Test
    void overviewContainsOnlyAccessibleStoresAndCombinesFreshnessWithPersistedIssues() {
        UUID userId = UUID.randomUUID();
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        DataQualityIssue issue = issue(
                firstId,
                "SALE",
                "SALE_ITEM_NET_MISMATCH",
                DataQualitySeverity.ERROR,
                Instant.parse("2026-07-22T09:00:00Z")
        );
        when(storeCatalogService.findAccessible(userId, UserRole.MANAGER))
                .thenReturn(List.of(store(firstId, "First"), store(secondId, "Second")));
        when(issueRepository.findAllByStoreIdInAndStatus(
                List.of(firstId, secondId), DataQualityStatus.OPEN
        )).thenReturn(List.of(issue));
        when(dataStatusService.get(firstId)).thenReturn(dataStatus(
                firstId, StoreDataFreshnessStatus.STALE, 2
        ));
        when(dataStatusService.get(secondId)).thenReturn(dataStatus(
                secondId, StoreDataFreshnessStatus.CURRENT, 0
        ));

        DataQualityOverviewView result = service.overview(userId, UserRole.MANAGER);

        assertThat(result.storeCount()).isEqualTo(2);
        assertThat(result.errorStoreCount()).isEqualTo(1);
        assertThat(result.warningStoreCount()).isZero();
        assertThat(result.okStoreCount()).isEqualTo(1);
        assertThat(result.openIssueCount()).isEqualTo(2);
        assertThat(result.stores()).extracting(StoreDataQualitySummaryView::storeId)
                .containsExactly(firstId, secondId);
        assertThat(result.stores().getFirst().errorCount()).isEqualTo(1);
        assertThat(result.stores().getFirst().warningCount()).isEqualTo(1);
    }

    @Test
    void detailUsesStableSafeIssueMessagesAndDoesNotExposeSourceEntityId() {
        UUID storeId = UUID.randomUUID();
        Store store = mock(Store.class);
        when(store.getName()).thenReturn("Central");
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));
        when(dataStatusService.get(storeId)).thenReturn(dataStatus(
                storeId, StoreDataFreshnessStatus.ERROR, null
        ));
        DataQualityIssue issue = issue(
                storeId,
                "SALE",
                "UNKNOWN_WITH_RAW_MESSAGE",
                DataQualitySeverity.WARNING,
                Instant.parse("2026-07-22T08:00:00Z")
        );
        when(issueRepository.findAllByStoreIdInAndStatus(
                List.of(storeId), DataQualityStatus.OPEN
        )).thenReturn(List.of(issue));

        StoreDataQualityView result = service.get(storeId);

        assertThat(result.summary().status()).isEqualTo(DataQualityHealthStatus.ERROR);
        assertThat(result.summary().openIssueCount()).isEqualTo(2);
        assertThat(result.issues()).extracting(DataQualityIssueView::code)
                .containsExactly("SYNC_FAILED", "UNKNOWN_WITH_RAW_MESSAGE");
        assertThat(result.issues().get(1).message())
                .isEqualTo("Data consistency issue requires review");
        assertThat(result.issues().get(1).key()).startsWith("QUALITY_ISSUE:");
    }

    @Test
    void unknownStoreUsesStableNotFoundException() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(storeId))
                .isInstanceOf(StoreNotFoundException.class);
    }

    private DataQualityIssue issue(
            UUID storeId,
            String entityType,
            String code,
            DataQualitySeverity severity,
            Instant detectedAt
    ) {
        DataQualityIssue issue = mock(DataQualityIssue.class);
        when(issue.getId()).thenReturn(UUID.randomUUID());
        when(issue.getStoreId()).thenReturn(storeId);
        when(issue.getEntityType()).thenReturn(entityType);
        when(issue.getIssueCode()).thenReturn(code);
        when(issue.getSeverity()).thenReturn(severity);
        when(issue.getDetectedAt()).thenReturn(detectedAt);
        return issue;
    }

    private StoreSummaryView store(UUID storeId, String name) {
        return new StoreSummaryView(
                storeId,
                name,
                null,
                "Europe/Moscow",
                LocalTime.MIDNIGHT,
                LocalTime.of(10, 0),
                LocalTime.of(21, 0),
                true
        );
    }

    private StoreDataStatusView dataStatus(
            UUID storeId,
            StoreDataFreshnessStatus status,
            Integer lagDays
    ) {
        return new StoreDataStatusView(
                storeId,
                status,
                LocalDate.of(2026, 7, 21),
                status == StoreDataFreshnessStatus.NOT_SYNCED
                        ? null : LocalDate.of(2026, 7, 20),
                LocalDate.of(2026, 7, 20),
                LocalDate.of(2026, 7, 20),
                lagDays,
                Instant.parse("2026-07-22T07:00:00Z"),
                new StoreSyncActivityView(false, null, null, null, null, null, null),
                0,
                status == StoreDataFreshnessStatus.ERROR ? "sensitive raw error" : null,
                status == StoreDataFreshnessStatus.ERROR
                        ? Instant.parse("2026-07-22T09:30:00Z") : null,
                CHECKED_AT
        );
    }
}
