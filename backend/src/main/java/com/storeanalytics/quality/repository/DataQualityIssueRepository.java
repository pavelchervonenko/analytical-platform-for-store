package com.storeanalytics.quality.repository;

import com.storeanalytics.quality.model.DataQualityIssue;
import com.storeanalytics.quality.model.DataQualityStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DataQualityIssueRepository extends JpaRepository<DataQualityIssue, UUID> {

    Optional<DataQualityIssue> findByEntityTypeAndEntityIdAndIssueCodeAndStatus(
            String entityType,
            String entityId,
            String issueCode,
            DataQualityStatus status
    );

    long countByStatus(DataQualityStatus status);

    @Query("""
            select issue
            from DataQualityIssue issue
            where issue.store.id in :storeIds
              and issue.status = :status
            """)
    List<DataQualityIssue> findAllByStoreIdInAndStatus(
            @Param("storeIds") Collection<UUID> storeIds,
            @Param("status") DataQualityStatus status
    );
}
