package com.storeanalytics.sync.repository;

import com.storeanalytics.sync.model.RawRecordVersion;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RawRecordVersionRepository extends JpaRepository<RawRecordVersion, UUID> {

    @Query(value = """
            SELECT *
            FROM raw_record_versions
            WHERE store_id IS NULL
              AND connection_id = :connectionId
              AND source_system = :sourceSystem
              AND entity_type = :entityType
              AND external_id = :externalId
              AND payload_hash = :payloadHash
            """, nativeQuery = true)
    Optional<RawRecordVersion> findCompanyRecordVersion(
            @Param("connectionId") UUID connectionId,
            @Param("sourceSystem") String sourceSystem,
            @Param("entityType") String entityType,
            @Param("externalId") String externalId,
            @Param("payloadHash") String payloadHash
    );

    @Query(value = """
            SELECT *
            FROM raw_record_versions
            WHERE store_id = :storeId
              AND connection_id = :connectionId
              AND source_system = :sourceSystem
              AND entity_type = :entityType
              AND external_id = :externalId
              AND payload_hash = :payloadHash
            """, nativeQuery = true)
    Optional<RawRecordVersion> findStoreRecordVersion(
            @Param("connectionId") UUID connectionId,
            @Param("storeId") UUID storeId,
            @Param("sourceSystem") String sourceSystem,
            @Param("entityType") String entityType,
            @Param("externalId") String externalId,
            @Param("payloadHash") String payloadHash
    );
}
