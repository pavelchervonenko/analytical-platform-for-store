package com.storeanalytics.store.model;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import com.storeanalytics.common.persistence.AbstractMutableEntity;
import com.storeanalytics.integration.connection.model.IntegrationConnection;
import com.storeanalytics.sync.model.SourceSystem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "cash_registers")
public class CashRegister extends AbstractMutableEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "connection_id")
    private IntegrationConnection connection;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_system", nullable = false)
    private SourceSystem sourceSystem;

    @Column(name = "external_id", nullable = false)
    private String externalId;

    @Column(nullable = false)
    private String name;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String metadata;

    protected CashRegister() {
    }

    public static CashRegister fromLiveSklad(
            IntegrationConnection connection,
            Store store,
            String externalId,
            String name
    ) {
        requireNonNull(connection, "connection");
        require(connection.getSourceSystem() == SourceSystem.LIVESKLAD,
                "connection must be a LiveSklad connection");
        requireNonNull(store, "store");
        require(store.isConnectedTo(connection),
                "cash register and store must belong to the same connection");
        return new CashRegister(connection, store, SourceSystem.LIVESKLAD, externalId, name);
    }

    public static CashRegister manual(Store store, String externalId, String name) {
        return new CashRegister(null, store, SourceSystem.MANUAL, externalId, name);
    }

    private CashRegister(
            IntegrationConnection connection,
            Store store,
            SourceSystem sourceSystem,
            String externalId,
            String name
    ) {
        this.connection = connection;
        this.store = requireNonNull(store, "store");
        this.sourceSystem = requireNonNull(sourceSystem, "sourceSystem");
        this.externalId = requireText(externalId, "externalId");
        this.name = requireText(name, "name");
        this.active = true;
        this.metadata = "{}";
    }
    public boolean updateFromLiveSklad(Store updatedStore, String updatedName) {
        Store validatedStore = requireNonNull(updatedStore, "store");
        require(sourceSystem == SourceSystem.LIVESKLAD && connection != null,
                "only a LiveSklad cash register can be updated from LiveSklad");
        require(validatedStore.isConnectedTo(connection),
                "cash register and store must belong to the same connection");
        String validatedName = requireText(updatedName, "name");
        boolean changed = !sameStore(store, validatedStore)
                || !name.equals(validatedName)
                || !active;
        store = validatedStore;
        name = validatedName;
        active = true;
        return changed;
    }

    public boolean markInactive() {
        if (!active) {
            return false;
        }
        active = false;
        return true;
    }

    public IntegrationConnection getConnection() {
        return connection;
    }

    public Store getStore() {
        return store;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getName() {
        return name;
    }

    public boolean isActive() {
        return active;
    }

    private boolean sameStore(Store first, Store second) {
        return first == second
                || first != null
                && second != null
                && first.getId() != null
                && first.getId().equals(second.getId());
    }
}
