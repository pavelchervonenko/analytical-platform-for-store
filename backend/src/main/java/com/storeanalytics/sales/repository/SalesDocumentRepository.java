package com.storeanalytics.sales.repository;

import com.storeanalytics.sales.model.SalesDocument;
import com.storeanalytics.sales.model.SalesDocumentKind;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SalesDocumentRepository extends JpaRepository<SalesDocument, UUID> {

    Optional<SalesDocument> findByConnectionIdAndExternalId(
            UUID connectionId,
            String externalId
    );

    List<SalesDocument> findAllByConnectionIdAndStoreIdAndDocumentKindAndOccurredAtBetween(
            UUID connectionId,
            UUID storeId,
            SalesDocumentKind documentKind,
            Instant periodStart,
            Instant periodEnd
    );
}
