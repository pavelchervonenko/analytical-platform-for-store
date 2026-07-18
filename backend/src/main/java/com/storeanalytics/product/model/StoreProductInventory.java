package com.storeanalytics.product.model;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.store.model.Store;
import com.storeanalytics.sync.model.SyncRun;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "store_product_inventory")
public class StoreProductInventory {

    @EmbeddedId
    private StoreProductInventoryId id;

    @MapsId("storeId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @MapsId("productId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal quantity;

    @Column(name = "retail_price", precision = 19, scale = 2)
    private BigDecimal retailPrice;

    @Column(name = "cost_amount", precision = 19, scale = 2)
    private BigDecimal costAmount;

    @Column(name = "source_updated_at")
    private Instant sourceUpdatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "last_sync_run_id")
    private SyncRun lastSyncRun;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String metadata;

    @Version
    @Column(nullable = false)
    private long version;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected StoreProductInventory() {
    }

    public StoreProductInventory(
            Store store,
            Product product,
            InventoryValues values,
            SyncRun lastSyncRun
    ) {
        this.store = requireNonNull(store, "store");
        this.product = requireNonNull(product, "product");
        require(product.isCompatibleWith(store),
                "product and store must belong to the same connection");
        this.id = new StoreProductInventoryId(store.getId(), product.getId());
        update(values, lastSyncRun);
        this.metadata = "{}";
    }

    public void update(InventoryValues values, SyncRun lastSyncRun) {
        requireNonNull(values, "values");
        this.quantity = values.quantity();
        this.retailPrice = values.retailPrice();
        this.costAmount = values.costAmount();
        this.sourceUpdatedAt = values.sourceUpdatedAt();
        this.lastSyncRun = lastSyncRun;
    }

    public StoreProductInventoryId getId() {
        return id;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
