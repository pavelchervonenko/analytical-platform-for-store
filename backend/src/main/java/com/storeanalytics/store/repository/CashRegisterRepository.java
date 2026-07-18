package com.storeanalytics.store.repository;

import com.storeanalytics.store.model.CashRegister;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CashRegisterRepository extends JpaRepository<CashRegister, UUID> {

    Optional<CashRegister> findByConnectionIdAndExternalId(
            UUID connectionId,
            String externalId
    );

    List<CashRegister> findAllByConnectionIdAndStoreId(
            UUID connectionId,
            UUID storeId
    );
}
