package com.storeanalytics.performance.model;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

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
@Table(name = "rating_schemes")
public class RatingScheme extends AbstractCreatedEntity {

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(name = "effective_from", nullable = false, updatable = false)
    private LocalDate effectiveFrom;

    @Column(name = "contribution_weight", nullable = false, precision = 5, scale = 2, updatable = false)
    private BigDecimal contributionWeight;

    @Column(name = "efficiency_weight", nullable = false, precision = 5, scale = 2, updatable = false)
    private BigDecimal efficiencyWeight;

    @Column(name = "structure_weight", nullable = false, precision = 5, scale = 2, updatable = false)
    private BigDecimal structureWeight;

    @Column(name = "attach_weight", nullable = false, precision = 5, scale = 2, updatable = false)
    private BigDecimal attachWeight;

    @Column(name = "accessory_structure_weight", nullable = false, precision = 5, scale = 2, updatable = false)
    private BigDecimal accessoryStructureWeight;

    @Column(name = "service_structure_weight", nullable = false, precision = 5, scale = 2, updatable = false)
    private BigDecimal serviceStructureWeight;

    @Column(name = "minimum_attach_denominator", nullable = false, precision = 19, scale = 3, updatable = false)
    private BigDecimal minimumAttachDenominator;

    @Column(name = "score_cap", nullable = false, precision = 6, scale = 2, updatable = false)
    private BigDecimal scoreCap;

    @Column(name = "minimum_coverage_percent", nullable = false, precision = 5, scale = 2, updatable = false)
    private BigDecimal minimumCoveragePercent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    protected RatingScheme() {
    }

    public RatingScheme(
            String code,
            LocalDate effectiveFrom,
            RatingSchemeDefinition definition,
            AppUser createdBy
    ) {
        this.code = requireText(code, "code");
        this.effectiveFrom = requireNonNull(effectiveFrom, "effectiveFrom");
        RatingSchemeDefinition validated = requireNonNull(definition, "definition");
        contributionWeight = validated.contributionWeight();
        efficiencyWeight = validated.efficiencyWeight();
        structureWeight = validated.structureWeight();
        attachWeight = validated.attachWeight();
        accessoryStructureWeight = validated.accessoryStructureWeight();
        serviceStructureWeight = validated.serviceStructureWeight();
        minimumAttachDenominator = validated.minimumAttachDenominator();
        scoreCap = validated.scoreCap();
        minimumCoveragePercent = validated.minimumCoveragePercent();
        this.createdBy = createdBy;
    }

    public String getCode() {
        return code;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public BigDecimal getContributionWeight() {
        return contributionWeight;
    }

    public BigDecimal getEfficiencyWeight() {
        return efficiencyWeight;
    }

    public BigDecimal getStructureWeight() {
        return structureWeight;
    }

    public BigDecimal getAttachWeight() {
        return attachWeight;
    }

    public BigDecimal getAccessoryStructureWeight() {
        return accessoryStructureWeight;
    }

    public BigDecimal getServiceStructureWeight() {
        return serviceStructureWeight;
    }

    public BigDecimal getMinimumAttachDenominator() {
        return minimumAttachDenominator;
    }

    public BigDecimal getScoreCap() {
        return scoreCap;
    }

    public BigDecimal getMinimumCoveragePercent() {
        return minimumCoveragePercent;
    }

    public AppUser getCreatedBy() {
        return createdBy;
    }
}
