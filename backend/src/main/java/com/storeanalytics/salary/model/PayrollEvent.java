package com.storeanalytics.salary.model;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.common.persistence.AbstractCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "payroll_events")
public class PayrollEvent extends AbstractCreatedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payroll_run_id", nullable = false, updatable = false)
    private PayrollRun payrollRun;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false)
    private PayrollEventType eventType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_user_id", updatable = false)
    private AppUser actorUser;

    @Column(updatable = false)
    private String details;

    protected PayrollEvent() {
    }

    public PayrollEvent(
            PayrollRun payrollRun,
            PayrollEventType eventType,
            AppUser actorUser,
            String details
    ) {
        this.payrollRun = requireNonNull(payrollRun, "payrollRun");
        this.eventType = requireNonNull(eventType, "eventType");
        this.actorUser = actorUser;
        this.details = details;
    }

    public PayrollEventType getEventType() {
        return eventType;
    }

    public AppUser getActorUser() {
        return actorUser;
    }

    public String getDetails() {
        return details;
    }
}
