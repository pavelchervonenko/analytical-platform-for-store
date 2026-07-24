package com.storeanalytics.performance.repository;

import com.storeanalytics.performance.model.StorePerformancePlan;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StorePerformancePlanRepository
        extends JpaRepository<StorePerformancePlan, UUID> {

    Optional<StorePerformancePlan> findByStoreIdAndPlanMonth(UUID storeId, LocalDate planMonth);

    List<StorePerformancePlan> findAllByStoreIdAndPlanMonthBetweenOrderByPlanMonth(
            UUID storeId,
            LocalDate firstMonth,
            LocalDate lastMonth
    );
}
