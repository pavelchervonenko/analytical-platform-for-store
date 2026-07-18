package com.storeanalytics.quality.repository;

import com.storeanalytics.quality.model.DataQualityIssue;
import com.storeanalytics.quality.model.DataQualityStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DataQualityIssueRepository extends JpaRepository<DataQualityIssue, UUID> {

    Optional<DataQualityIssue> findByEntityTypeAndEntityIdAndIssueCodeAndStatus(
            String entityType,
            String entityId,
            String issueCode,
            DataQualityStatus status
    );
}
