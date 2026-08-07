package com.storeanalytics.sales.repository;

import com.storeanalytics.sales.model.SalesDocumentItem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SalesDocumentItemRepository extends JpaRepository<SalesDocumentItem, UUID> {

    @Query("""
            SELECT count(item)
            FROM SalesDocumentItem item
            WHERE item.product.connection.id = :connectionId
              AND item.analyticsCategory.code = 'UNMAPPED'
              AND item.deleted = false
              AND item.salesDocument.deleted = false
            """)
    long countActiveUnmappedByConnectionId(
            @Param("connectionId") UUID connectionId
    );

    List<SalesDocumentItem> findAllBySalesDocumentId(UUID salesDocumentId);

    Optional<SalesDocumentItem> findBySalesDocumentIdAndExternalId(
            UUID salesDocumentId,
            String externalId
    );
}
