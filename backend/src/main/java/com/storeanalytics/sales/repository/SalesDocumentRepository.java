package com.storeanalytics.sales.repository;

import com.storeanalytics.sales.model.SalesDocument;
import com.storeanalytics.sales.model.SalesDocumentKind;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SalesDocumentRepository extends JpaRepository<SalesDocument, UUID> {

    Optional<SalesDocument> findByConnectionIdAndExternalId(
            UUID connectionId,
            String externalId
    );

    @Query("""
            SELECT document
            FROM SalesDocument document
            WHERE document.connection.id = :connectionId
              AND document.store.id = :storeId
              AND document.documentKind = :documentKind
              AND document.sourceDocumentType = :sourceDocumentType
              AND document.occurredAt BETWEEN :periodStart AND :periodEnd
            """)
    List<SalesDocument> findAllByConnectionIdAndStoreIdAndDocumentKindAndOccurredAtBetween(
            @Param("connectionId") UUID connectionId,
            @Param("storeId") UUID storeId,
            @Param("documentKind") SalesDocumentKind documentKind,
            @Param("sourceDocumentType") String sourceDocumentType,
            @Param("periodStart") Instant periodStart,
            @Param("periodEnd") Instant periodEnd
    );
}
