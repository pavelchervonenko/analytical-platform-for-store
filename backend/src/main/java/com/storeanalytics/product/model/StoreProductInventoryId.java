package com.storeanalytics.product.model;

import static com.storeanalytics.common.validation.ModelValidation.requirePersistedId;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class StoreProductInventoryId implements Serializable {

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    protected StoreProductInventoryId() {
    }

    public StoreProductInventoryId(UUID storeId, UUID productId) {
        this.storeId = requirePersistedId(storeId, "storeId");
        this.productId = requirePersistedId(productId, "productId");
    }

    public UUID getStoreId() {
        return storeId;
    }

    public UUID getProductId() {
        return productId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof StoreProductInventoryId that)) {
            return false;
        }
        return Objects.equals(storeId, that.storeId) && Objects.equals(productId, that.productId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(storeId, productId);
    }
}
