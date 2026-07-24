package com.storeanalytics.performance.repository;

import com.storeanalytics.performance.model.EmployeeRatingSnapshot;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmployeeRatingSnapshotRepository
        extends JpaRepository<EmployeeRatingSnapshot, UUID> {

    Optional<EmployeeRatingSnapshot> findByStoreIdAndPeriodStartAndPeriodEnd(
            UUID storeId,
            LocalDate periodStart,
            LocalDate periodEnd
    );
}
