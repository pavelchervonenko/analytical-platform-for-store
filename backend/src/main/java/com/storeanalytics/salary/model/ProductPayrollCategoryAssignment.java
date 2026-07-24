package com.storeanalytics.salary.model;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;
import static com.storeanalytics.common.validation.ModelValidation.require;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.common.persistence.AbstractMutableEntity;
import com.storeanalytics.product.model.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;

@Entity
@Table(name = "product_payroll_category_assignments")
public class ProductPayrollCategoryAssignment extends AbstractMutableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @Enumerated(EnumType.STRING)
    @Column(name = "payroll_category_code", nullable = false, updatable = false)
    private PayrollCategoryCode categoryCode;

    @Column(name = "valid_from", nullable = false, updatable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_by", updatable = false)
    private AppUser assignedBy;

    @Column(name = "change_reason", nullable = false, updatable = false)
    private String changeReason;

    protected ProductPayrollCategoryAssignment() {
    }

    public ProductPayrollCategoryAssignment(
            Product product,
            PayrollCategoryCode categoryCode,
            LocalDate validFrom,
            AppUser assignedBy,
            String changeReason
    ) {
        this.product = requireNonNull(product, "product");
        this.categoryCode = assignable(categoryCode);
        this.validFrom = requireNonNull(validFrom, "validFrom");
        this.assignedBy = assignedBy;
        this.changeReason = requireText(changeReason, "changeReason");
    }

    public void close(LocalDate endDate) {
        LocalDate validated = requireNonNull(endDate, "validTo");
        require(validated.isAfter(validFrom), "validTo must be after validFrom");
        require(validTo == null, "payroll category assignment is already closed");
        validTo = validated;
    }

    private PayrollCategoryCode assignable(PayrollCategoryCode value) {
        PayrollCategoryCode validated = requireNonNull(value, "categoryCode");
        require(validated != PayrollCategoryCode.UNMAPPED,
                "UNMAPPED cannot be assigned explicitly");
        return validated;
    }

    public Product getProduct() {
        return product;
    }

    public PayrollCategoryCode getCategoryCode() {
        return categoryCode;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public LocalDate getValidTo() {
        return validTo;
    }

    public AppUser getAssignedBy() {
        return assignedBy;
    }

    public String getChangeReason() {
        return changeReason;
    }
}
