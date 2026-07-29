package com.storeanalytics.performance.model;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.common.persistence.AbstractMutableEntity;
import com.storeanalytics.store.model.Store;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;

@Entity
@Table(name = "work_schedule_day_revisions")
public class WorkScheduleDayRevision extends AbstractMutableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(name = "work_date", nullable = false, updatable = false)
    private LocalDate workDate;

    @Column(name = "revision", nullable = false)
    private long revision;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private AppUser updatedBy;

    protected WorkScheduleDayRevision() {
    }

    public WorkScheduleDayRevision(Store store, LocalDate workDate, AppUser updatedBy) {
        this.store = requireNonNull(store, "store");
        this.workDate = requireNonNull(workDate, "workDate");
        this.updatedBy = requireNonNull(updatedBy, "updatedBy");
        revision = 1;
    }

    public void advance(AppUser actor) {
        updatedBy = requireNonNull(actor, "updatedBy");
        revision++;
    }

    public Store getStore() {
        return store;
    }

    public LocalDate getWorkDate() {
        return workDate;
    }

    public long getRevision() {
        return revision;
    }

    public AppUser getUpdatedBy() {
        return updatedBy;
    }
}
