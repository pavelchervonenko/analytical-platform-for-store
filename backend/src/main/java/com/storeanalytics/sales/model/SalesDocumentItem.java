package com.storeanalytics.sales.model;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.common.persistence.AbstractMutableEntity;
import com.storeanalytics.product.model.AnalyticsCategory;
import com.storeanalytics.product.model.Product;
import com.storeanalytics.product.model.ProductCategoryAssignment;
import com.storeanalytics.product.model.ProductConditionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "sales_document_items")
public class SalesDocumentItem extends AbstractMutableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sales_document_id", nullable = false)
    private SalesDocument salesDocument;

    @Column(name = "external_id", nullable = false)
    private String externalId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "original_item_id")
    private SalesDocumentItem originalItem;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "product_name_snapshot", nullable = false)
    private String productNameSnapshot;

    @Column(name = "source_group_name_snapshot")
    private String sourceGroupNameSnapshot;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "analytics_category_id", nullable = false)
    private AnalyticsCategory analyticsCategory;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_assignment_id")
    private ProductCategoryAssignment categoryAssignment;

    @Column(name = "classification_version")
    private String classificationVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "condition_type_snapshot", nullable = false)
    private ProductConditionType conditionTypeSnapshot;

    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal quantity;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "gross_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal grossAmount;

    @Column(name = "discount_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal discountAmount;

    @Column(name = "net_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal netAmount;

    @Column(name = "cost_amount", precision = 19, scale = 2)
    private BigDecimal costAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "cost_quality", nullable = false)
    private CostQuality costQuality;

    @Column(name = "is_work", nullable = false)
    private boolean work;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String metadata;

    protected SalesDocumentItem() {
    }

    public SalesDocumentItem(
            SalesItemIdentity identity,
            SalesItemClassification classification,
            SalesItemAmounts amounts,
            CostQuality costQuality,
            boolean work
    ) {
        requireNonNull(identity, "identity");
        requireNonNull(classification, "classification");
        requireNonNull(amounts, "amounts");
        require(identity.salesDocument().accepts(identity.product()),
                "sales item product must be compatible with the document store");
        require(identity.originalItem() == null
                        || identity.salesDocument().isReturnOf(
                        identity.originalItem().getSalesDocument()),
                "return item original must belong to its original sale");
        require(identity.originalItem() == null
                        || sameEntity(
                        identity.originalItem().getProduct(), identity.product()),
                "return item product must match its original sale item");
        require(classification.categoryAssignment() == null
                        || classification.categoryAssignment().matches(
                        identity.product(), classification.analyticsCategory()),
                "category assignment must match the item product and category");
        this.salesDocument = identity.salesDocument();
        this.externalId = identity.externalId();
        this.originalItem = identity.originalItem();
        this.product = identity.product();
        this.productNameSnapshot = classification.productNameSnapshot();
        this.sourceGroupNameSnapshot = classification.sourceGroupNameSnapshot();
        this.analyticsCategory = classification.analyticsCategory();
        this.categoryAssignment = classification.categoryAssignment();
        this.classificationVersion = classification.classificationVersion();
        this.conditionTypeSnapshot = classification.conditionType();
        this.quantity = amounts.quantity();
        this.unitPrice = amounts.unitPrice();
        this.grossAmount = amounts.grossAmount();
        this.discountAmount = amounts.discountAmount();
        this.netAmount = amounts.netAmount();
        this.costAmount = amounts.costAmount();
        this.costQuality = requireNonNull(costQuality, "costQuality");
        this.work = work;
        this.deleted = false;
        this.metadata = "{}";
    }
    public boolean update(
            Product updatedProduct,
            SalesItemClassification classification,
            SalesItemAmounts amounts,
            CostQuality updatedCostQuality,
            boolean updatedWork
    ) {
        return update(
                originalItem,
                updatedProduct,
                classification,
                amounts,
                updatedCostQuality,
                updatedWork
        );
    }

    public boolean update(
            SalesDocumentItem updatedOriginalItem,
            Product updatedProduct,
            SalesItemClassification classification,
            SalesItemAmounts amounts,
            CostQuality updatedCostQuality,
            boolean updatedWork
    ) {
        requireNonNull(updatedProduct, "product");
        requireNonNull(classification, "classification");
        requireNonNull(amounts, "amounts");
        require(salesDocument.accepts(updatedProduct),
                "sales item product must be compatible with the document store");
        require(updatedOriginalItem == null
                        || salesDocument.isReturnOf(
                        updatedOriginalItem.getSalesDocument()),
                "return item original must belong to its original sale");
        require(updatedOriginalItem == null
                        || sameEntity(
                        updatedOriginalItem.getProduct(), updatedProduct),
                "return item product must match its original sale item");
        require(classification.categoryAssignment() == null
                        || classification.categoryAssignment().matches(
                        updatedProduct, classification.analyticsCategory()),
                "category assignment must match the item product and category");

        boolean changed = deleted
                || !sameEntity(originalItem, updatedOriginalItem)
                || !sameEntity(product, updatedProduct)
                || !Objects.equals(productNameSnapshot, classification.productNameSnapshot())
                || !Objects.equals(sourceGroupNameSnapshot, classification.sourceGroupNameSnapshot())
                || !sameEntity(analyticsCategory, classification.analyticsCategory())
                || !sameEntity(categoryAssignment, classification.categoryAssignment())
                || !Objects.equals(classificationVersion, classification.classificationVersion())
                || conditionTypeSnapshot != classification.conditionType()
                || quantity.compareTo(amounts.quantity()) != 0
                || unitPrice.compareTo(amounts.unitPrice()) != 0
                || grossAmount.compareTo(amounts.grossAmount()) != 0
                || discountAmount.compareTo(amounts.discountAmount()) != 0
                || netAmount.compareTo(amounts.netAmount()) != 0
                || !sameAmount(costAmount, amounts.costAmount())
                || costQuality != updatedCostQuality
                || work != updatedWork;

        originalItem = updatedOriginalItem;
        product = updatedProduct;
        productNameSnapshot = classification.productNameSnapshot();
        sourceGroupNameSnapshot = classification.sourceGroupNameSnapshot();
        analyticsCategory = classification.analyticsCategory();
        categoryAssignment = classification.categoryAssignment();
        classificationVersion = classification.classificationVersion();
        conditionTypeSnapshot = classification.conditionType();
        quantity = amounts.quantity();
        unitPrice = amounts.unitPrice();
        grossAmount = amounts.grossAmount();
        discountAmount = amounts.discountAmount();
        netAmount = amounts.netAmount();
        costAmount = amounts.costAmount();
        costQuality = requireNonNull(updatedCostQuality, "costQuality");
        work = updatedWork;
        deleted = false;
        return changed;
    }

    public boolean markDeleted() {
        if (deleted) {
            return false;
        }
        deleted = true;
        return true;
    }

    public SalesDocument getSalesDocument() {
        return salesDocument;
    }

    public String getExternalId() {
        return externalId;
    }
    public Product getProduct() {
        return product;
    }

    public SalesDocumentItem getOriginalItem() {
        return originalItem;
    }

    public SalesItemClassification classificationSnapshot() {
        return new SalesItemClassification(
                productNameSnapshot,
                sourceGroupNameSnapshot,
                analyticsCategory,
                categoryAssignment,
                classificationVersion,
                conditionTypeSnapshot
        );
    }

    public boolean isDeleted() {
        return deleted;
    }

    private boolean sameAmount(BigDecimal first, BigDecimal second) {
        return first == null ? second == null : second != null && first.compareTo(second) == 0;
    }

    private boolean sameEntity(
            com.storeanalytics.common.persistence.AbstractCreatedEntity first,
            com.storeanalytics.common.persistence.AbstractCreatedEntity second
    ) {
        return first == second
                || first != null
                && second != null
                && first.getId() != null
                && first.getId().equals(second.getId());
    }
}
