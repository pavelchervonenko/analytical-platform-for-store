package com.storeanalytics.employee.model;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import com.storeanalytics.common.persistence.AbstractMutableEntity;
import com.storeanalytics.integration.connection.model.IntegrationConnection;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.sync.model.SourceSystem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "employees")
public class Employee extends AbstractMutableEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "connection_id", updatable = false)
    private IntegrationConnection connection;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_system", nullable = false, updatable = false)
    private SourceSystem sourceSystem;

    @Column(name = "external_id", nullable = false, updatable = false)
    private String externalId;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "source_updated_at")
    private Instant sourceUpdatedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String metadata;

    protected Employee() {
    }

    public static Employee fromLiveSklad(
            IntegrationConnection connection,
            String externalId,
            String fullName,
            Instant sourceUpdatedAt
    ) {
        requireNonNull(connection, "connection");
        if (connection.getSourceSystem() != SourceSystem.LIVESKLAD) {
            throw new IllegalArgumentException("connection must be a LiveSklad connection");
        }
        return new Employee(
                connection,
                SourceSystem.LIVESKLAD,
                requireText(externalId, "externalId"),
                fullName,
                sourceUpdatedAt
        );
    }

    public static Employee manual(String externalId, String fullName) {
        return new Employee(
                null,
                SourceSystem.MANUAL,
                requireText(externalId, "externalId"),
                fullName,
                null
        );
    }

    private Employee(
            IntegrationConnection connection,
            SourceSystem sourceSystem,
            String externalId,
            String fullName,
            Instant sourceUpdatedAt
    ) {
        this.connection = connection;
        this.sourceSystem = sourceSystem;
        this.externalId = externalId;
        this.fullName = requireText(fullName, "fullName");
        this.active = true;
        this.sourceUpdatedAt = sourceUpdatedAt;
        this.metadata = "{}";
    }

    public boolean isCompatibleWith(Store store) {
        return sourceSystem == SourceSystem.MANUAL
                || requireNonNull(store, "store").isConnectedTo(connection);
    }

    public boolean updateFromLiveSklad(
            String sourceFullName,
            boolean sourceActive,
            Instant sourceUpdatedAt
    ) {
        if (sourceSystem != SourceSystem.LIVESKLAD) {
            throw new IllegalStateException("only LiveSklad employees can use source updates");
        }
        if (this.sourceUpdatedAt != null && sourceUpdatedAt != null
                && sourceUpdatedAt.isBefore(this.sourceUpdatedAt)) {
            return false;
        }
        return updateState(sourceFullName, sourceActive, sourceUpdatedAt);
    }

    public boolean updateManual(String newFullName, boolean newActive) {
        if (sourceSystem != SourceSystem.MANUAL) {
            throw new IllegalStateException("only manual employees can use manual updates");
        }
        return updateState(newFullName, newActive, null);
    }

    private boolean updateState(String newFullName, boolean newActive, Instant newSourceUpdatedAt) {
        String validatedName = requireText(newFullName, "fullName");
        Instant effectiveSourceUpdatedAt = newSourceUpdatedAt == null ? sourceUpdatedAt : newSourceUpdatedAt;
        boolean changed = !Objects.equals(fullName, validatedName)
                || active != newActive
                || !Objects.equals(sourceUpdatedAt, effectiveSourceUpdatedAt);
        if (changed) {
            fullName = validatedName;
            active = newActive;
            sourceUpdatedAt = effectiveSourceUpdatedAt;
        }
        return changed;
    }

    public SourceSystem getSourceSystem() {
        return sourceSystem;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getFullName() {
        return fullName;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getSourceUpdatedAt() {
        return sourceUpdatedAt;
    }
}
