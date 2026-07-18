package com.storeanalytics.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.generator.EventType;

@MappedSuperclass
public abstract class AbstractCreatedEntity {

    @Id
    @UuidGenerator
    private UUID id;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    public UUID getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
