package com.storeanalytics.performance.repository;

import com.storeanalytics.performance.model.EmployeeWorkShift;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmployeeWorkShiftRepository extends JpaRepository<EmployeeWorkShift, UUID> {

    List<EmployeeWorkShift> findAllByStoreIdAndWorkDateBetweenOrderByWorkDateAscEmployeeFullNameAsc(
            UUID storeId,
            LocalDate periodStart,
            LocalDate periodEnd
    );

    List<EmployeeWorkShift> findAllByStoreIdAndWorkDate(UUID storeId, LocalDate workDate);

    Optional<EmployeeWorkShift> findByEmployeeIdAndWorkDate(UUID employeeId, LocalDate workDate);
}
