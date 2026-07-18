package com.storeanalytics.store.model;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import com.storeanalytics.integration.connection.model.IntegrationConnection;
import com.storeanalytics.sync.model.SourceSystem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.generator.EventType;

@Entity
@Table(name = "stores")
public class Store {

    private static final String REPORTING_TIMEZONE = "Europe/Kaliningrad";
    private static final LocalTime BUSINESS_DAY_START = LocalTime.MIDNIGHT;
    private static final LocalTime OPENS_AT = LocalTime.of(10, 0);
    private static final LocalTime CLOSES_AT = LocalTime.of(21, 0);

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "connection_id", updatable = false)
    private IntegrationConnection connection;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_system", nullable = false, updatable = false)
    private SourceSystem sourceSystem;

    @Column(name = "external_id", updatable = false)
    private String externalId;

    @Column(nullable = false)
    private String name;

    private String address;

    @Column(nullable = false)
    private String timezone;

    @Column(name = "business_day_start", nullable = false)
    private LocalTime businessDayStart;

    @Column(name = "opens_at", nullable = false)
    private LocalTime opensAt;

    @Column(name = "closes_at", nullable = false)
    private LocalTime closesAt;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Version
    @Column(nullable = false)
    private long version;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected Store() {
    }

    public static Store fromLiveSklad(
            IntegrationConnection connection,
            String externalId,
            String name,
            String address
    ) {
        requireNonNull(connection, "connection");
        require(connection.getSourceSystem() == SourceSystem.LIVESKLAD,
                "connection must be a LiveSklad connection");
        return create(
                connection, SourceSystem.LIVESKLAD, requireText(externalId, "externalId"),
                name, address, new StoreSchedule(
                        REPORTING_TIMEZONE, BUSINESS_DAY_START, OPENS_AT, CLOSES_AT)
        );
    }

    public static Store manual(
            String externalId,
            String name,
            String address,
            StoreSchedule schedule
    ) {
        return create(
                null, SourceSystem.MANUAL, externalId, name, address, schedule
        );
    }

    private static Store create(
            IntegrationConnection connection,
            SourceSystem sourceSystem,
            String externalId,
            String name,
            String address,
            StoreSchedule schedule
    ) {
        requireNonNull(sourceSystem, "sourceSystem");
        requireNonNull(schedule, "schedule");
        Store store = new Store();
        store.connection = connection;
        store.sourceSystem = sourceSystem;
        store.externalId = externalId;
        store.name = requireText(name, "name");
        store.address = address;
        store.timezone = schedule.timezone();
        store.businessDayStart = schedule.businessDayStart();
        store.opensAt = schedule.opensAt();
        store.closesAt = schedule.closesAt();
        store.active = true;
        return store;
    }

    public boolean updateFromLiveSklad(String sourceName, String sourceAddress) {
        requireText(sourceName, "sourceName");
        boolean changed = !Objects.equals(name, sourceName)
                || !Objects.equals(address, sourceAddress)
                || !active;
        if (changed) {
            name = sourceName;
            address = sourceAddress;
            active = true;
        }
        return changed;
    }

    public UUID getId() {
        return id;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getName() {
        return name;
    }

    public String getAddress() {
        return address;
    }

    public String getTimezone() {
        return timezone;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isConnectedTo(IntegrationConnection candidate) {
        return sourceSystem == SourceSystem.LIVESKLAD
                && connection != null
                && connection.matches(candidate);
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
