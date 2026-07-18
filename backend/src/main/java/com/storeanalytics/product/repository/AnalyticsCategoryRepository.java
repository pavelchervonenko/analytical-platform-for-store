package com.storeanalytics.product.repository;

import com.storeanalytics.product.model.AnalyticsCategory;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalyticsCategoryRepository extends JpaRepository<AnalyticsCategory, UUID> {

    Optional<AnalyticsCategory> findByCode(String code);
}
