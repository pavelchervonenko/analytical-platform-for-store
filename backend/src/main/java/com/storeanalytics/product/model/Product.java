package com.storeanalytics.product.model;

import static com.storeanalytics.common.validation.ModelValidation.require;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Objects;
@Entity
@Table(name = "products")
public class Product extends AbstractMutableEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "connection_id")
    private IntegrationConnection connection;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_system", nullable = false)
    private SourceSystem sourceSystem;

    @Column(name = "external_id", nullable = false)
    private String externalId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_group_id")
    private SourceProductGroup sourceGroup;

    private String code;

    private String sku;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_kind", nullable = false)
    private ProductSourceKind sourceKind;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "source_updated_at")
    private Instant sourceUpdatedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String metadata;

    protected Product() {
    }

    public static Product fromLiveSklad(
            IntegrationConnection connection,
            String externalId,
            ProductDetails details
    ) {
        requireNonNull(connection, "connection");
        require(connection.getSourceSystem() == SourceSystem.LIVESKLAD,
                "connection must be a LiveSklad connection");
        return new Product(connection, SourceSystem.LIVESKLAD, externalId, details);
    }

    public static Product manual(String externalId, ProductDetails details) {
        return new Product(null, SourceSystem.MANUAL, externalId, details);
    }

    private Product(
            IntegrationConnection connection,
            SourceSystem sourceSystem,
            String externalId,
            ProductDetails details
    ) {
        requireNonNull(details, "details");
        require(details.sourceGroup() == null
                        || details.sourceGroup().matches(sourceSystem, connection),
                "source group must belong to the same source and connection");
        this.connection = connection;
        this.sourceSystem = requireNonNull(sourceSystem, "sourceSystem");
        this.externalId = requireText(externalId, "externalId");
        this.sourceGroup = details.sourceGroup();
        this.code = details.code();
        this.sku = details.sku();
        this.name = details.name();
        this.sourceKind = details.sourceKind();
        this.active = true;
        this.sourceUpdatedAt = details.sourceUpdatedAt();
        this.metadata = "{}";
    }

    public boolean updateFromLiveSklad(ProductDetails details) {
        requireNonNull(details, "details");
        require(sourceSystem == SourceSystem.LIVESKLAD && connection != null,
                "only a LiveSklad product can be updated from LiveSklad");
        require(details.sourceGroup() == null
                        || details.sourceGroup().matches(sourceSystem, connection),
                "source group must belong to the same source and connection");

        boolean reactivated = !active;
        active = true;
        Instant candidateUpdatedAt = details.sourceUpdatedAt();
        if (sourceUpdatedAt != null
                && (candidateUpdatedAt == null || candidateUpdatedAt.isBefore(sourceUpdatedAt))) {
            return reactivated;
        }

        boolean changed = reactivated
                || !sameEntity(sourceGroup, details.sourceGroup())
                || !Objects.equals(code, details.code())
                || !Objects.equals(sku, details.sku())
                || !Objects.equals(name, details.name())
                || sourceKind != details.sourceKind()
                || !Objects.equals(sourceUpdatedAt, candidateUpdatedAt);
        sourceGroup = details.sourceGroup();
        code = details.code();
        sku = details.sku();
        name = details.name();
        sourceKind = details.sourceKind();
        if (candidateUpdatedAt != null || sourceUpdatedAt == null) {
            sourceUpdatedAt = candidateUpdatedAt;
        }
        return changed;
    }

    public boolean isProvisionalCatalogIdentity() {
        return sourceSystem == SourceSystem.LIVESKLAD
                && connection != null
                && sourceKind == ProductSourceKind.UNKNOWN
                && Objects.equals(externalId, code);
    }

    public boolean claimLiveSkladIdentity(
            String liveSkladExternalId,
            ProductDetails details
    ) {
        requireNonNull(details, "details");
        require(isProvisionalCatalogIdentity(),
                "only a provisional catalog product can claim an identity");
        require(details.code() != null && details.code().equals(code),
                "LiveSklad product code must match the provisional identity");
        String validatedExternalId = requireText(
                liveSkladExternalId,
                "liveSkladExternalId"
        );
        boolean identityChanged = !externalId.equals(validatedExternalId);
        externalId = validatedExternalId;
        return updateFromLiveSklad(details) || identityChanged;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public ProductSourceKind getSourceKind() {
        return sourceKind;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getSourceUpdatedAt() {
        return sourceUpdatedAt;
    }

    private boolean sameEntity(SourceProductGroup first, SourceProductGroup second) {
        return first == second
                || first != null
                && second != null
                && first.getId() != null
                && first.getId().equals(second.getId());
    }

    public boolean isCompatibleWith(Store store) {
        return sourceSystem == SourceSystem.MANUAL
                || requireNonNull(store, "store").isConnectedTo(connection);
    }
}
