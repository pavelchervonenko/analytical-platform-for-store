package com.storeanalytics.product.model;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.common.persistence.AbstractCreatedEntity;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.sync.model.SyncRun;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "store_product_inventory_history")
public class StoreProductInventoryHistory extends AbstractCreatedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal quantity;

    @Column(name = "retail_price", precision = 19, scale = 2)
    private BigDecimal retailPrice;

    @Column(name = "cost_amount", precision = 19, scale = 2)
    private BigDecimal costAmount;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    @Column(name = "source_updated_at")
    private Instant sourceUpdatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sync_run_id")
    private SyncRun syncRun;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String metadata;

    protected StoreProductInventoryHistory() {
    }

    public StoreProductInventoryHistory(
            Store store,
            Product product,
            InventoryValues values,
            Instant observedAt,
            SyncRun syncRun
    ) {
        requireNonNull(values, "values");
        this.store = requireNonNull(store, "store");
        this.product = requireNonNull(product, "product");
        require(product.isCompatibleWith(store),
                "product and store must belong to the same connection");
        this.quantity = values.quantity();
        this.retailPrice = values.retailPrice();
        this.costAmount = values.costAmount();
        this.observedAt = requireNonNull(observedAt, "observedAt");
        this.sourceUpdatedAt = values.sourceUpdatedAt();
        this.syncRun = requireNonNull(syncRun, "syncRun");
        this.metadata = "{}";
    }
}
