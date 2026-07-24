package com.storeanalytics.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.auth.repository.UserStoreAccessRepository;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.repository.StoreRepository;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StoreCatalogServiceTest {

    private StoreRepository storeRepository;
    private UserStoreAccessRepository accessRepository;
    private StoreCatalogService service;

    @BeforeEach
    void setUp() {
        storeRepository = mock(StoreRepository.class);
        accessRepository = mock(UserStoreAccessRepository.class);
        service = new StoreCatalogService(storeRepository, accessRepository);
    }

    @Test
    void administratorReceivesEveryActiveStoreInDisplayOrder() {
        UUID userId = UUID.randomUUID();
        Store second = store("Моби Сфера", true);
        Store first = store("Future Store", true);
        when(storeRepository.findAllByActiveTrue()).thenReturn(List.of(second, first));

        List<StoreSummaryView> result = service.findAccessible(userId, UserRole.ADMIN);

        assertThat(result).extracting(StoreSummaryView::name)
                .containsExactly("Future Store", "Моби Сфера");
        assertThat(result.getFirst().opensAt()).isEqualTo(LocalTime.of(10, 0));
        assertThat(result.getFirst().closesAt()).isEqualTo(LocalTime.of(21, 0));
        verify(accessRepository, never()).findActiveStoresByUserId(userId);
    }

    @Test
    void managerReceivesOnlyActiveAssignedStores() {
        UUID userId = UUID.randomUUID();
        Store allowed = store("Доступный", true);
        when(accessRepository.findActiveStoresByUserId(userId)).thenReturn(List.of(allowed));

        List<StoreSummaryView> result = service.findAccessible(userId, UserRole.MANAGER);

        assertThat(result).singleElement()
                .satisfies(view -> assertThat(view.name()).isEqualTo("Доступный"));
        verify(storeRepository, never()).findAllByActiveTrue();
        verify(accessRepository).findActiveStoresByUserId(userId);
    }

    private Store store(String name, boolean active) {
        Store store = mock(Store.class);
        when(store.getId()).thenReturn(UUID.randomUUID());
        when(store.getName()).thenReturn(name);
        when(store.getAddress()).thenReturn("Адрес");
        when(store.getTimezone()).thenReturn("Europe/Kaliningrad");
        when(store.getBusinessDayStart()).thenReturn(LocalTime.MIDNIGHT);
        when(store.getOpensAt()).thenReturn(LocalTime.of(10, 0));
        when(store.getClosesAt()).thenReturn(LocalTime.of(21, 0));
        when(store.isActive()).thenReturn(active);
        return store;
    }
}
