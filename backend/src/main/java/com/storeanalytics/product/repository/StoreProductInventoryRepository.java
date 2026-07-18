package com.storeanalytics.product.repository;

import com.storeanalytics.product.model.StoreProductInventory;
import com.storeanalytics.product.model.StoreProductInventoryId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreProductInventoryRepository
        extends JpaRepository<StoreProductInventory, StoreProductInventoryId> {
}
