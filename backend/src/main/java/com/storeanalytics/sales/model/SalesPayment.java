package com.storeanalytics.sales.model;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNegative;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import com.storeanalytics.common.persistence.AbstractMutableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "sales_payments")
public class SalesPayment extends AbstractMutableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sales_document_id", nullable = false)
    private SalesDocument salesDocument;

    @Column(name = "external_id", nullable = false)
    private String externalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false)
    private PaymentMethod paymentMethod;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String metadata;

    protected SalesPayment() {
    }

    public SalesPayment(
            SalesDocument salesDocument,
            String externalId,
            PaymentMethod paymentMethod,
            BigDecimal amount,
            Instant paidAt
    ) {
        this.salesDocument = requireNonNull(salesDocument, "salesDocument");
        this.externalId = requireText(externalId, "externalId");
        this.paymentMethod = requireNonNull(paymentMethod, "paymentMethod");
        this.amount = requireNonNegative(amount, "amount", 19, 2);
        this.paidAt = paidAt;
        this.deleted = false;
        this.metadata = "{}";
    }
    public boolean update(
            PaymentMethod updatedMethod,
            BigDecimal updatedAmount,
            Instant updatedPaidAt
    ) {
        PaymentMethod validatedMethod = requireNonNull(updatedMethod, "paymentMethod");
        BigDecimal validatedAmount = requireNonNegative(updatedAmount, "amount", 19, 2);
        boolean changed = deleted
                || paymentMethod != validatedMethod
                || amount.compareTo(validatedAmount) != 0
                || !java.util.Objects.equals(paidAt, updatedPaidAt);
        paymentMethod = validatedMethod;
        amount = validatedAmount;
        paidAt = updatedPaidAt;
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

    public boolean isDeleted() {
        return deleted;
    }
}
