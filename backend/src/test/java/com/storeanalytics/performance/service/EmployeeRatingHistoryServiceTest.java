package com.storeanalytics.performance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.performance.exception.RatingPeriodNotClosedException;
import com.storeanalytics.performance.model.EmployeeRatingSnapshot;
import com.storeanalytics.performance.repository.EmployeeRatingSnapshotRepository;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.repository.StoreRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EmployeeRatingHistoryServiceTest {

    private final UUID storeId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final StoreKpiPeriod period = new StoreKpiPeriod(
            LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)
    );

    private EmployeeRatingService ratingService;
    private EmployeeRatingSnapshotRepository snapshotRepository;
    private EmployeeRatingSnapshotCodec snapshotCodec;
    private StoreRepository storeRepository;
    private AppUserRepository userRepository;

    @BeforeEach
    void setUp() {
        ratingService = mock(EmployeeRatingService.class);
        snapshotRepository = mock(EmployeeRatingSnapshotRepository.class);
        snapshotCodec = mock(EmployeeRatingSnapshotCodec.class);
        storeRepository = mock(StoreRepository.class);
        userRepository = mock(AppUserRepository.class);
    }

    @Test
    void queryCalculatesLiveResultWhenSnapshotDoesNotExist() {
        EmployeeRatingResult live = mock(EmployeeRatingResult.class);
        when(snapshotRepository.findByStoreIdAndPeriodStartAndPeriodEnd(
                storeId, period.start(), period.end()
        )).thenReturn(Optional.empty());
        when(ratingService.calculate(storeId, period)).thenReturn(live);

        EmployeeRatingResult result = new EmployeeRatingQueryService(
                ratingService, snapshotRepository, snapshotCodec
        ).get(storeId, period);

        assertThat(result).isSameAs(live);
        verifyNoInteractions(snapshotCodec);
    }

    @Test
    void queryReturnsSnapshotWithoutRecalculatingSources() {
        EmployeeRatingSnapshot snapshot = mock(EmployeeRatingSnapshot.class);
        EmployeeRatingResult finalized = mock(EmployeeRatingResult.class);
        when(snapshotRepository.findByStoreIdAndPeriodStartAndPeriodEnd(
                storeId, period.start(), period.end()
        )).thenReturn(Optional.of(snapshot));
        when(snapshotCodec.decode(snapshot)).thenReturn(finalized);

        EmployeeRatingResult result = new EmployeeRatingQueryService(
                ratingService, snapshotRepository, snapshotCodec
        ).get(storeId, period);

        assertThat(result).isSameAs(finalized);
        verifyNoInteractions(ratingService);
    }

    @Test
    void finalizationPersistsOneImmutableSnapshot() {
        Store store = store();
        AppUser actor = mock(AppUser.class);
        when(actor.getDisplayName()).thenReturn("Rating Manager");
        EmployeeRatingResult live = mock(EmployeeRatingResult.class);
        RatingFormulaView formula = mock(RatingFormulaView.class);
        EmployeeRatingSnapshot saved = mock(EmployeeRatingSnapshot.class);
        EmployeeRatingResult finalized = mock(EmployeeRatingResult.class);
        when(storeRepository.findByIdForUpdate(storeId)).thenReturn(Optional.of(store));
        when(snapshotRepository.findByStoreIdAndPeriodStartAndPeriodEnd(
                storeId, period.start(), period.end()
        )).thenReturn(Optional.empty());
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        when(ratingService.calculate(storeId, period)).thenReturn(live);
        when(live.formula()).thenReturn(formula);
        when(formula.version()).thenReturn("employee-rating-v1");
        when(snapshotCodec.encode(live)).thenReturn("{\"history\":{\"status\":\"LIVE\"}}");
        when(snapshotCodec.sha256(any())).thenReturn("a".repeat(64));
        when(snapshotRepository.saveAndFlush(any(EmployeeRatingSnapshot.class)))
                .thenReturn(saved);
        when(snapshotCodec.decode(saved)).thenReturn(finalized);

        EmployeeRatingResult result = finalizationService().finalizePeriod(
                storeId, period, actorId
        );

        assertThat(result).isSameAs(finalized);
        verify(snapshotRepository).saveAndFlush(any(EmployeeRatingSnapshot.class));
    }

    @Test
    void repeatedFinalizationReturnsExistingSnapshotWithoutRecalculation() {
        Store store = store();
        EmployeeRatingSnapshot existing = mock(EmployeeRatingSnapshot.class);
        EmployeeRatingResult finalized = mock(EmployeeRatingResult.class);
        when(storeRepository.findByIdForUpdate(storeId)).thenReturn(Optional.of(store));
        when(snapshotRepository.findByStoreIdAndPeriodStartAndPeriodEnd(
                storeId, period.start(), period.end()
        )).thenReturn(Optional.of(existing));
        when(snapshotCodec.decode(existing)).thenReturn(finalized);

        EmployeeRatingResult result = finalizationService().finalizePeriod(
                storeId, period, actorId
        );

        assertThat(result).isSameAs(finalized);
        verifyNoInteractions(ratingService, userRepository);
        verify(snapshotRepository, never()).saveAndFlush(any());
    }

    @Test
    void cannotFinalizePeriodUntilItsEndDateHasPassedInStoreTimezone() {
        StoreKpiPeriod openPeriod = new StoreKpiPeriod(
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 2)
        );
        Store store = store();
        when(storeRepository.findByIdForUpdate(storeId)).thenReturn(Optional.of(store));
        when(snapshotRepository.findByStoreIdAndPeriodStartAndPeriodEnd(
                storeId, openPeriod.start(), openPeriod.end()
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() -> finalizationService().finalizePeriod(
                storeId, openPeriod, actorId
        )).isInstanceOf(RatingPeriodNotClosedException.class);

        verifyNoInteractions(ratingService, userRepository);
    }

    private EmployeeRatingFinalizationService finalizationService() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-08-02T08:00:00Z"), ZoneOffset.UTC
        );
        return new EmployeeRatingFinalizationService(
                ratingService,
                snapshotRepository,
                snapshotCodec,
                storeRepository,
                userRepository,
                clock,
                mock(com.storeanalytics.audit.service.AuditLogService.class)
        );
    }

    private Store store() {
        Store store = mock(Store.class);
        when(store.getTimezone()).thenReturn("Europe/Kaliningrad");
        return store;
    }
}
