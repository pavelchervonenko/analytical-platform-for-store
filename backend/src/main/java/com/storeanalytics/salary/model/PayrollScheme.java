package com.storeanalytics.salary.model;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;
import static com.storeanalytics.common.validation.ModelValidation.require;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.common.persistence.AbstractCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "payroll_schemes")
public class PayrollScheme extends AbstractCreatedEntity {

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(name = "effective_from", nullable = false, updatable = false)
    private LocalDate effectiveFrom;

    @Column(name = "achieved_percentage", nullable = false, precision = 5, scale = 2, updatable = false)
    private BigDecimal achievedPercentage;

    @Column(name = "missed_percentage", nullable = false, precision = 5, scale = 2, updatable = false)
    private BigDecimal missedPercentage;

    @Column(name = "achieved_tier1_rate", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal achievedTier1Rate;

    @Column(name = "missed_tier1_rate", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal missedTier1Rate;

    @Column(name = "achieved_tier2_rate", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal achievedTier2Rate;

    @Column(name = "missed_tier2_rate", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal missedTier2Rate;

    @Column(name = "advance_amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal advanceAmount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    protected PayrollScheme() {
    }

    public PayrollScheme(
            String code,
            LocalDate effectiveFrom,
            PayrollSchemeDefinition definition,
            AppUser createdBy
    ) {
        this.code = requireText(code, "code");
        this.effectiveFrom = requireNonNull(effectiveFrom, "effectiveFrom");
        require(this.effectiveFrom.getDayOfMonth() == 1,
                "effectiveFrom must be the first day of a month");
        PayrollSchemeDefinition validated = requireNonNull(definition, "definition");
        achievedPercentage = validated.achievedPercentage();
        missedPercentage = validated.missedPercentage();
        achievedTier1Rate = validated.achievedTier1Rate();
        missedTier1Rate = validated.missedTier1Rate();
        achievedTier2Rate = validated.achievedTier2Rate();
        missedTier2Rate = validated.missedTier2Rate();
        advanceAmount = validated.advanceAmount();
        this.createdBy = createdBy;
    }

    public String getCode() {
        return code;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public BigDecimal getAchievedPercentage() {
        return achievedPercentage;
    }

    public BigDecimal getMissedPercentage() {
        return missedPercentage;
    }

    public BigDecimal getAchievedTier1Rate() {
        return achievedTier1Rate;
    }

    public BigDecimal getMissedTier1Rate() {
        return missedTier1Rate;
    }

    public BigDecimal getAchievedTier2Rate() {
        return achievedTier2Rate;
    }

    public BigDecimal getMissedTier2Rate() {
        return missedTier2Rate;
    }

    public BigDecimal getAdvanceAmount() {
        return advanceAmount;
    }

    public AppUser getCreatedBy() {
        return createdBy;
    }
}
