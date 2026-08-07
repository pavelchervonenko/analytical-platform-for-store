package com.storeanalytics.product.model;


import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import com.storeanalytics.common.persistence.AbstractMutableEntity;
import com.storeanalytics.salary.model.PayrollCategoryCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

@Entity
@Table(name = "analytics_categories")
public class AnalyticsCategory extends AbstractMutableEntity {

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "category_kind", nullable = false)
    private AnalyticsCategoryKind categoryKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "device_family", nullable = false)
    private DeviceFamily deviceFamily;

    @Column(name = "counts_as_phone", nullable = false)
    private boolean countsAsPhone;

    @Column(name = "counts_as_device", nullable = false)
    private boolean countsAsDevice;

    @Column(name = "counts_as_additional_revenue", nullable = false)
    private boolean countsAsAdditionalRevenue;

    @Enumerated(EnumType.STRING)
    @Column(name = "attach_denominator_code")
    private AttachDenominatorCode attachDenominatorCode;

    @Column(name = "requires_same_document_for_attach", nullable = false)
    private boolean requiresSameDocumentForAttach;

    @Generated(event = EventType.INSERT)
    @Enumerated(EnumType.STRING)
    @Column(name = "payroll_category_code", nullable = false, insertable = false, updatable = false)
    private PayrollCategoryCode payrollCategoryCode;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    protected AnalyticsCategory() {
    }

    public AnalyticsCategory(
            String code,
            String name,
            String description,
            AnalyticsCategoryRules rules
    ) {
        requireNonNull(rules, "rules");
        this.code = requireText(code, "code");
        this.name = requireText(name, "name");
        this.description = description;
        this.categoryKind = rules.categoryKind();
        this.deviceFamily = rules.deviceFamily();
        this.countsAsPhone = rules.countsAsPhone();
        this.countsAsDevice = rules.countsAsDevice();
        this.countsAsAdditionalRevenue = rules.countsAsAdditionalRevenue();
        this.attachDenominatorCode = rules.attachDenominatorCode();
        this.requiresSameDocumentForAttach = rules.requiresSameDocumentForAttach();
        this.active = true;
    }
    public String getCode() {
        return code;
    }

    public boolean permitsZeroCost() {
        return categoryKind == AnalyticsCategoryKind.SERVICE
                || categoryKind == AnalyticsCategoryKind.WARRANTY
                || categoryKind == AnalyticsCategoryKind.PROTECTION;
    }

    public PayrollCategoryCode getPayrollCategoryCode() {
        return payrollCategoryCode;
    }
}
