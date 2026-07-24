package com.storeanalytics.performance.model;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.require;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.common.persistence.AbstractMutableEntity;
import com.storeanalytics.store.model.Store;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "store_performance_plans")
public class StorePerformancePlan extends AbstractMutableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(name = "plan_month", nullable = false, updatable = false)
    private LocalDate planMonth;

    @Column(name = "revenue_target", nullable = false, precision = 19, scale = 2)
    private BigDecimal revenueTarget;

    @Column(name = "accessory_share_target", nullable = false, precision = 5, scale = 2)
    private BigDecimal accessoryShareTarget;

    @Column(name = "service_share_target", nullable = false, precision = 5, scale = 2)
    private BigDecimal serviceShareTarget;

    @Column(name = "additional_share_target", nullable = false, precision = 5, scale = 2)
    private BigDecimal additionalShareTarget;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private AppUser updatedBy;

    protected StorePerformancePlan() {
    }

    public StorePerformancePlan(
            Store store,
            LocalDate planMonth,
            StorePlanTargets targets,
            AppUser updatedBy
    ) {
        this.store = requireNonNull(store, "store");
        this.planMonth = requireMonthStart(planMonth);
        apply(targets, updatedBy);
    }

    public void update(StorePlanTargets targets, AppUser actor) {
        apply(targets, actor);
    }

    private void apply(StorePlanTargets targets, AppUser actor) {
        StorePlanTargets validated = requireNonNull(targets, "targets");
        revenueTarget = validated.revenue();
        accessoryShareTarget = validated.accessorySharePercent();
        serviceShareTarget = validated.serviceSharePercent();
        additionalShareTarget = validated.additionalSharePercent();
        updatedBy = requireNonNull(actor, "updatedBy");
    }

    private LocalDate requireMonthStart(LocalDate value) {
        LocalDate validated = requireNonNull(value, "planMonth");
        require(validated.getDayOfMonth() == 1, "planMonth must be the first day of a month");
        return validated;
    }

    public Store getStore() {
        return store;
    }

    public LocalDate getPlanMonth() {
        return planMonth;
    }

    public BigDecimal getRevenueTarget() {
        return revenueTarget;
    }

    public BigDecimal getAccessoryShareTarget() {
        return accessoryShareTarget;
    }

    public BigDecimal getServiceShareTarget() {
        return serviceShareTarget;
    }

    public BigDecimal getAdditionalShareTarget() {
        return additionalShareTarget;
    }

    public AppUser getUpdatedBy() {
        return updatedBy;
    }
}
