package com.storeanalytics.store.repository;

import com.storeanalytics.store.model.Store;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoreRepository extends JpaRepository<Store, UUID> {

    Optional<Store> findByConnectionIdAndExternalId(UUID connectionId, String externalId);

    List<Store> findAllByConnectionIdAndActiveTrueOrderByExternalId(UUID connectionId);

    List<Store> findAllByActiveTrue();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select store from Store store where store.id = :storeId")
    Optional<Store> findByIdForUpdate(@Param("storeId") UUID storeId);
}
