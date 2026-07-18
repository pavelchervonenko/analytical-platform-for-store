package com.storeanalytics.product.model;

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
import java.time.Instant;

@Entity
@Table(name = "product_category_assignments")
public class ProductCategoryAssignment extends AbstractCreatedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "analytics_category_id", nullable = false)
    private AnalyticsCategory analyticsCategory;

    @Enumerated(EnumType.STRING)
    @Column(name = "condition_type", nullable = false)
    private ProductConditionType conditionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "assignment_source", nullable = false)
    private CategoryAssignmentSource assignmentSource;

    @Column(name = "rule_version")
    private String ruleVersion;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_to")
    private Instant validTo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_by")
    private AppUser assignedBy;

    @Column(name = "change_reason")
    private String changeReason;

    protected ProductCategoryAssignment() {
    }

    public ProductCategoryAssignment(
            Product product,
            AnalyticsCategory analyticsCategory,
            CategoryAssignmentDetails details
    ) {
        requireNonNull(details, "details");
        this.product = requireNonNull(product, "product");
        this.analyticsCategory = requireNonNull(analyticsCategory, "analyticsCategory");
        this.conditionType = details.conditionType();
        this.assignmentSource = details.assignmentSource();
        this.ruleVersion = details.ruleVersion();
        this.validFrom = details.validFrom();
        this.validTo = details.validTo();
        this.assignedBy = details.assignedBy();
        this.changeReason = details.changeReason();
    }

    public AnalyticsCategory getAnalyticsCategory() {
        return analyticsCategory;
    }

    public ProductConditionType getConditionType() {
        return conditionType;
    }

    public String getRuleVersion() {
        return ruleVersion;
    }

    public boolean matches(Product candidateProduct, AnalyticsCategory candidateCategory) {
        return sameProduct(candidateProduct) && sameCategory(candidateCategory);
    }

    private boolean sameProduct(Product candidate) {
        return product == candidate
                || product.getId() != null
                && candidate != null
                && product.getId().equals(candidate.getId());
    }

    private boolean sameCategory(AnalyticsCategory candidate) {
        return analyticsCategory == candidate
                || analyticsCategory.getId() != null
                && candidate != null
                && analyticsCategory.getId().equals(candidate.getId());
    }
}
