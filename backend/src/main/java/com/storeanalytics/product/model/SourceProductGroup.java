package com.storeanalytics.product.model;

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
@Table(name = "source_product_groups")
public class SourceProductGroup extends AbstractMutableEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "connection_id")
    private IntegrationConnection connection;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_system", nullable = false)
    private SourceSystem sourceSystem;

    @Column(name = "external_id")
    private String externalId;

    @Column(nullable = false)
    private String path;

    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private SourceProductGroup parent;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String metadata;

    protected SourceProductGroup() {
    }

    public static SourceProductGroup fromLiveSklad(
            IntegrationConnection connection,
            String externalId,
            String path,
            String name,
            SourceProductGroup parent
    ) {
        requireNonNull(connection, "connection");
        require(connection.getSourceSystem() == SourceSystem.LIVESKLAD,
                "connection must be a LiveSklad connection");
        return new SourceProductGroup(
                connection, SourceSystem.LIVESKLAD, externalId, path, name, parent
        );
    }

    public static SourceProductGroup manual(
            String externalId,
            String path,
            String name,
            SourceProductGroup parent
    ) {
        return new SourceProductGroup(null, SourceSystem.MANUAL, externalId, path, name, parent);
    }

    private SourceProductGroup(
            IntegrationConnection connection,
            SourceSystem sourceSystem,
            String externalId,
            String path,
            String name,
            SourceProductGroup parent
    ) {
        require(parent == null || parent.matches(sourceSystem, connection),
                "parent group must belong to the same source and connection");
        this.connection = connection;
        this.sourceSystem = requireNonNull(sourceSystem, "sourceSystem");
        this.externalId = externalId;
        this.path = requireText(path, "path");
        this.name = requireText(name, "name");
        this.parent = parent;
        this.active = true;
        this.metadata = "{}";
    }

    public String getName() {
        return name;
    }

    public boolean matches(SourceSystem candidateSource, IntegrationConnection candidateConnection) {
        return sourceSystem == candidateSource
                && (sourceSystem == SourceSystem.MANUAL
                || connection != null && connection.matches(candidateConnection));
    }
}
