package com.storeanalytics.product.service;

import com.storeanalytics.common.database.CatalogActivationState;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Event-time boundary; never infer activation from application startup or import time. */
@Component
@DependsOnDatabaseInitialization
public final class CatalogClassificationCutover {
    private static final ZoneId DEFAULT_BUSINESS_ZONE = ZoneId.of("Europe/Kaliningrad");
    private final Instant activateFrom;
    private final ZoneId businessZone;

    @Autowired
    public CatalogClassificationCutover(
            @Value("${app.catalog-classification.activate-from:}") String activateFrom, JdbcTemplate jdbc,
            @Value("${app.catalog-compatibility.snapshots-enabled:false}") boolean snapshotsEnabled,
            @Value("${app.catalog-compatibility.snapshots-from:}") String snapshotsFrom,
            ZoneId businessZone
    ) {
        this(activateFrom, jdbc, businessZone);
        if (this.activateFrom != null && !isBusinessDayBoundary()) {
            throw new IllegalStateException("Catalog activation must start at business-day midnight");
        }
        if (this.activateFrom != null && snapshotsEnabled
                && !this.activateFrom.equals(CatalogActivationState.parse(snapshotsFrom))) {
            throw new IllegalStateException("Catalog classification and snapshot capture boundaries must match");
        }
    }

    public CatalogClassificationCutover(
            @Value("${app.catalog-classification.activate-from:}") String activateFrom, JdbcTemplate jdbc
    ) {
        this(activateFrom, jdbc, DEFAULT_BUSINESS_ZONE);
    }

    private CatalogClassificationCutover(String activateFrom, JdbcTemplate jdbc, ZoneId businessZone) {
        this(activateFrom, businessZone);
        CatalogActivationState.verify(jdbc, this.activateFrom);
    }

    public CatalogClassificationCutover(
            @Value("${app.catalog-classification.activate-from:}") String activateFrom
    ) {
        this(activateFrom, DEFAULT_BUSINESS_ZONE);
    }

    private CatalogClassificationCutover(String activateFrom, ZoneId businessZone) {
        this.activateFrom = CatalogActivationState.parse(activateFrom);
        this.businessZone = Objects.requireNonNull(businessZone, "businessZone");
    }

    public boolean isBusinessDayBoundary() {
        return activateFrom != null
                && activateFrom.atZone(businessZone).toLocalTime().equals(LocalTime.MIDNIGHT);
    }

    public LocalDate activationBusinessDate() {
        return activationBoundary().orElseThrow(() ->
                new IllegalStateException("Catalog classification is not active"))
                .atZone(businessZone).toLocalDate();
    }

    public Optional<Instant> activationBoundary() {
        return Optional.ofNullable(activateFrom);
    }

    public boolean isConfigured() {
        return activateFrom != null;
    }

    public boolean requiresReviewForNewProduct(Instant createdAt, Instant occurredAt) {
        Objects.requireNonNull(occurredAt, "occurredAt");
        return activateFrom != null && !occurredAt.isBefore(activateFrom)
                && (createdAt == null || !createdAt.isBefore(activateFrom));
    }

    public boolean isHistorical(Instant occurredAt) {
        Objects.requireNonNull(occurredAt, "occurredAt");
        return activateFrom != null && occurredAt.isBefore(activateFrom);
    }
}
