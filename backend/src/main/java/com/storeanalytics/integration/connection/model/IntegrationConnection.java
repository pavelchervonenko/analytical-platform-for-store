package com.storeanalytics.integration.connection.model;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import com.storeanalytics.sync.model.SourceSystem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "integration_connections")
public class IntegrationConnection {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "connection_key", nullable = false)
    private String connectionKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_system", nullable = false)
    private SourceSystem sourceSystem;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "base_url")
    private String baseUrl;

    @Column(name = "credentials_ref")
    private String credentialsRef;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String settings;

    @Version
    @Column(nullable = false)
    private long version;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected IntegrationConnection() {
    }

    public IntegrationConnection(
            String connectionKey,
            SourceSystem sourceSystem,
            String displayName,
            String baseUrl,
            String credentialsRef
    ) {
        this.connectionKey = requireText(connectionKey, "connectionKey");
        this.sourceSystem = requireNonNull(sourceSystem, "sourceSystem");
        require(
                sourceSystem == SourceSystem.LIVESKLAD
                        || sourceSystem == SourceSystem.AMOCRM
                        || sourceSystem == SourceSystem.AI,
                "unsupported integration sourceSystem " + sourceSystem
        );
        this.displayName = requireText(displayName, "displayName");
        this.baseUrl = baseUrl;
        this.credentialsRef = credentialsRef;
        this.active = true;
        this.settings = "{}";
    }

    public UUID getId() {
        return id;
    }

    public String getConnectionKey() {
        return connectionKey;
    }

    public SourceSystem getSourceSystem() {
        return sourceSystem;
    }

    public boolean isActive() {
        return active;
    }

    public boolean matches(IntegrationConnection candidate) {
        return candidate != null
                && (this == candidate || id != null && id.equals(candidate.getId()));
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
