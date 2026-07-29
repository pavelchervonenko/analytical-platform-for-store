package com.storeanalytics.performance.repository;

import com.storeanalytics.performance.model.WorkScheduleDayRevision;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkScheduleDayRevisionRepository
        extends JpaRepository<WorkScheduleDayRevision, UUID> {

    Optional<WorkScheduleDayRevision> findByStoreIdAndWorkDate(
            UUID storeId,
            LocalDate workDate
    );
}
