package com.storeanalytics.product.repository;

import com.storeanalytics.product.model.SourceProductGroup;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceProductGroupRepository extends JpaRepository<SourceProductGroup, UUID> {
}
