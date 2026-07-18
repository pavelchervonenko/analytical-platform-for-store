package com.storeanalytics.employee.model;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.store.model.Store;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "employee_store_assignments")
public class EmployeeStoreAssignment {

    @EmbeddedId
    private EmployeeStoreAssignmentId id;

    @MapsId("employeeId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @MapsId("storeId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "participates_in_ranking", nullable = false)
    private boolean participatesInRanking;

    @Generated(event = EventType.INSERT)
    @Column(name = "assigned_at", nullable = false, insertable = false, updatable = false)
    private Instant assignedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String metadata;

    @Version
    @Column(nullable = false)
    private long version;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected EmployeeStoreAssignment() {
    }

    public EmployeeStoreAssignment(
            Employee employee,
            Store store,
            boolean participatesInRanking
    ) {
        this.employee = requireNonNull(employee, "employee");
        this.store = requireNonNull(store, "store");
        require(employee.isCompatibleWith(store),
                "employee and store must belong to the same connection");
        this.id = new EmployeeStoreAssignmentId(employee.getId(), store.getId());
        this.active = true;
        this.participatesInRanking = participatesInRanking;
        this.metadata = "{}";
    }

    public EmployeeStoreAssignmentId getId() {
        return id;
    }

    public Employee getEmployee() {
        return employee;
    }

    public Store getStore() {
        return store;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public boolean update(boolean active, boolean participatesInRanking) {
        boolean changed = this.active != active
                || this.participatesInRanking != participatesInRanking;
        this.active = active;
        this.participatesInRanking = participatesInRanking;
        return changed;
    }

    public boolean isActive() {
        return active;
    }

    public boolean participatesInRanking() {
        return participatesInRanking;
    }

    public long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
