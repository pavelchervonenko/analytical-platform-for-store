package com.storeanalytics.store.service;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.auth.repository.UserStoreAccessRepository;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.repository.StoreRepository;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StoreCatalogService {

    private static final Comparator<Store> DISPLAY_ORDER = Comparator
            .comparing(Store::getName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Store::getId);

    private final StoreRepository storeRepository;
    private final UserStoreAccessRepository accessRepository;

    public StoreCatalogService(
            StoreRepository storeRepository,
            UserStoreAccessRepository accessRepository
    ) {
        this.storeRepository = storeRepository;
        this.accessRepository = accessRepository;
    }

    @Transactional(readOnly = true)
    public List<StoreSummaryView> findAccessible(UUID userId, UserRole role) {
        UUID validatedUserId = requireNonNull(userId, "userId");
        UserRole validatedRole = requireNonNull(role, "role");
        List<Store> stores = validatedRole == UserRole.ADMIN
                ? storeRepository.findAllByActiveTrue()
                : accessRepository.findActiveStoresByUserId(validatedUserId);
        return stores.stream()
                .sorted(DISPLAY_ORDER)
                .map(this::toView)
                .toList();
    }

    private StoreSummaryView toView(Store store) {
        return new StoreSummaryView(
                store.getId(),
                store.getName(),
                store.getAddress(),
                store.getTimezone(),
                store.getBusinessDayStart(),
                store.getOpensAt(),
                store.getClosesAt(),
                store.isActive()
        );
    }
}
