package com.storeanalytics.sales.repository;

import com.storeanalytics.sales.model.SalesDocumentItem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SalesDocumentItemRepository extends JpaRepository<SalesDocumentItem, UUID> {

    List<SalesDocumentItem> findAllBySalesDocumentId(UUID salesDocumentId);

    Optional<SalesDocumentItem> findBySalesDocumentIdAndExternalId(
            UUID salesDocumentId,
            String externalId
    );
}
