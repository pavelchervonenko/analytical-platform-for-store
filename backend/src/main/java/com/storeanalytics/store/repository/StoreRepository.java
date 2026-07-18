package com.storeanalytics.store.repository;

import com.storeanalytics.store.model.Store;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreRepository extends JpaRepository<Store, UUID> {

    Optional<Store> findByConnectionIdAndExternalId(UUID connectionId, String externalId);

    List<Store> findAllByConnectionIdAndActiveTrueOrderByExternalId(UUID connectionId);
}
