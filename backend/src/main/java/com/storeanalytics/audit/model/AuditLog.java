package com.storeanalytics.audit.model;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.common.persistence.AbstractCreatedEntity;
import com.storeanalytics.store.model.Store;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.net.InetAddress;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "audit_log")
public class AuditLog extends AbstractCreatedEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_user_id")
    private AppUser actorUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id")
    private Store store;

    @Column(nullable = false)
    private String action;

    @Column(name = "entity_type")
    private String entityType;

    @Column(name = "entity_id")
    private String entityId;

    @Column(name = "ip_address", columnDefinition = "inet")
    private InetAddress ipAddress;

    @Column(name = "user_agent")
    private String userAgent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String metadata;

    @Enumerated(EnumType.STRING)
    @Column(name = "retention_class", nullable = false)
    private AuditRetentionClass retentionClass;

    @Column(name = "retain_until")
    private Instant retainUntil;

    protected AuditLog() {
    }

    public AuditLog(
            AppUser actorUser,
            Store store,
            String action,
            String entityType,
            String entityId,
            String metadata,
            AuditRetention retention
    ) {
        this.actorUser = actorUser;
        this.store = store;
        this.action = requireText(action, "action");
        this.entityType = requireText(entityType, "entityType");
        this.entityId = requireText(entityId, "entityId");
        this.ipAddress = null;
        this.userAgent = null;
        this.metadata = requireText(metadata, "metadata");
        applyRetention(retention);
    }

    private void applyRetention(AuditRetention retention) {
        AuditRetention validated = requireNonNull(retention, "retention");
        this.retentionClass = validated.retentionClass();
        this.retainUntil = validated.retainUntil();
    }

    public AppUser getActorUser() {
        return actorUser;
    }

    public Store getStore() {
        return store;
    }

    public String getAction() {
        return action;
    }

    public String getEntityType() {
        return entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public AuditRetentionClass getRetentionClass() {
        return retentionClass;
    }

    public Instant getRetainUntil() {
        return retainUntil;
    }

    public String getMetadata() {
        return metadata;
    }
}
