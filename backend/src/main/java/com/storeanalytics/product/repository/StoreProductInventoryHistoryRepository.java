package com.storeanalytics.product.repository;

import com.storeanalytics.product.model.StoreProductInventoryHistory;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreProductInventoryHistoryRepository
        extends JpaRepository<StoreProductInventoryHistory, UUID> {
}
