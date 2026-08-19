package com.storeanalytics.sales.model;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.common.persistence.AbstractMutableEntity;
import com.storeanalytics.employee.model.Employee;
import com.storeanalytics.integration.connection.model.IntegrationConnection;
import com.storeanalytics.product.model.Product;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.sync.model.RawRecordVersion;
import com.storeanalytics.sync.model.SourceSystem;
import com.storeanalytics.sync.model.SyncRun;
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
import java.time.LocalDate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.Objects;

@Entity
@Table(name = "sales_documents")
public class SalesDocument extends AbstractMutableEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "connection_id")
    private IntegrationConnection connection;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_system", nullable = false)
    private SourceSystem sourceSystem;

    @Column(name = "external_id", nullable = false)
    private String externalId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id")
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "original_document_id")
    private SalesDocument originalDocument;

    @Column(name = "document_number")
    private String documentNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_kind", nullable = false)
    private SalesDocumentKind documentKind;

    @Column(name = "source_document_type", nullable = false)
    private String sourceDocumentType;

    @Column(name = "source_status")
    private String sourceStatus;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "net_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal netAmount;

    @Column(name = "cost_amount", precision = 19, scale = 2)
    private BigDecimal costAmount;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted;

    @Column(name = "source_updated_at")
    private Instant sourceUpdatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "raw_record_version_id")
    private RawRecordVersion rawRecordVersion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "last_sync_run_id")
    private SyncRun lastSyncRun;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String metadata;

    protected SalesDocument() {
    }

    public SalesDocument(
            SalesDocumentIdentity identity,
            SalesDocumentDetails details,
            SalesDocumentAmounts amounts,
            RawRecordVersion rawRecordVersion
    ) {
        requireNonNull(identity, "identity");
        requireNonNull(details, "details");
        requireNonNull(amounts, "amounts");
        details.validateOriginalDocument(identity.originalDocument());
        this.connection = identity.connection();
        this.sourceSystem = identity.sourceSystem();
        this.externalId = identity.externalId();
        this.store = identity.store();
        this.employee = identity.employee();
        this.originalDocument = identity.originalDocument();
        this.documentNumber = details.documentNumber();
        this.documentKind = details.documentKind();
        this.sourceDocumentType = details.sourceDocumentType();
        this.sourceStatus = details.sourceStatus();
        this.occurredAt = details.occurredAt();
        this.businessDate = details.businessDate();
        this.netAmount = amounts.netAmount();
        this.costAmount = amounts.costAmount();
        this.deleted = false;
        this.sourceUpdatedAt = details.sourceUpdatedAt();
        this.rawRecordVersion = rawRecordVersion;
        this.lastSyncRun = identity.lastSyncRun();
        this.metadata = "{}";
    }

    public boolean isSale() {
        return documentKind == SalesDocumentKind.SALE;
    }

    public boolean isReturn() {
        return documentKind == SalesDocumentKind.RETURN;
    }

    public boolean isReturnOf(SalesDocument sale) {
        return isReturn() && sameEntity(originalDocument, sale);
    }

    public boolean accepts(Product product) {
        return requireNonNull(product, "product").isCompatibleWith(store);
    }
    public boolean updateFromLiveSklad(
            SalesDocumentIdentity identity,
            SalesDocumentDetails details,
            SalesDocumentAmounts amounts,
            RawRecordVersion updatedRawRecordVersion
    ) {
        requireNonNull(identity, "identity");
        requireNonNull(details, "details");
        requireNonNull(amounts, "amounts");
        require(sourceSystem == identity.sourceSystem()
                        && Objects.equals(externalId, identity.externalId())
                        && sameConnection(connection, identity.connection()),
                "sales document source identity cannot be changed");
        details.validateOriginalDocument(identity.originalDocument());

        if (!acceptsSourceVersion(details.sourceUpdatedAt())) {
            return false;
        }
        boolean reactivated = deleted;
        deleted = false;
        lastSyncRun = identity.lastSyncRun();

        boolean changed = reactivated
                || !sameStore(store, identity.store())
                || !sameEntity(employee, identity.employee())
                || !sameEntity(originalDocument, identity.originalDocument())
                || !Objects.equals(documentNumber, details.documentNumber())
                || documentKind != details.documentKind()
                || !Objects.equals(sourceDocumentType, details.sourceDocumentType())
                || !Objects.equals(sourceStatus, details.sourceStatus())
                || !Objects.equals(occurredAt, details.occurredAt())
                || !Objects.equals(businessDate, details.businessDate())
                || netAmount.compareTo(amounts.netAmount()) != 0
                || !sameAmount(costAmount, amounts.costAmount())
                || !Objects.equals(sourceUpdatedAt, details.sourceUpdatedAt())
                || !sameRawRecord(rawRecordVersion, updatedRawRecordVersion);

        store = identity.store();
        employee = identity.employee();
        originalDocument = identity.originalDocument();
        documentNumber = details.documentNumber();
        documentKind = details.documentKind();
        sourceDocumentType = details.sourceDocumentType();
        sourceStatus = details.sourceStatus();
        occurredAt = details.occurredAt();
        businessDate = details.businessDate();
        netAmount = amounts.netAmount();
        costAmount = amounts.costAmount();
        if (details.sourceUpdatedAt() != null || sourceUpdatedAt == null) {
            sourceUpdatedAt = details.sourceUpdatedAt();
        }
        rawRecordVersion = updatedRawRecordVersion;
        return changed;
    }

    public boolean acceptsSourceVersion(Instant candidate) {
        return sourceUpdatedAt == null
                || candidate != null && !candidate.isBefore(sourceUpdatedAt);
    }

    public boolean markDeleted(SyncRun syncRun) {
        SyncRun validatedRun = requireNonNull(syncRun, "syncRun");
        require(validatedRun.getSourceSystem() == sourceSystem
                        && sameConnection(connection, validatedRun.getConnection()),
                "sync run must match the sales document source");
        boolean changed = !deleted;
        deleted = true;
        lastSyncRun = validatedRun;
        return changed;
    }
    public boolean markDeletedFromLiveSklad(
            SyncRun syncRun,
            Instant candidateSourceUpdatedAt,
            RawRecordVersion updatedRawRecordVersion
    ) {
        SyncRun validatedRun = requireNonNull(syncRun, "syncRun");
        Instant validatedSourceUpdatedAt = requireNonNull(
                candidateSourceUpdatedAt,
                "sourceUpdatedAt"
        );
        RawRecordVersion validatedRawRecord = requireNonNull(
                updatedRawRecordVersion,
                "rawRecordVersion"
        );
        require(validatedRun.getSourceSystem() == sourceSystem
                        && sameConnection(connection, validatedRun.getConnection()),
                "sync run must match the sales document source");
        if (!acceptsSourceVersion(validatedSourceUpdatedAt)) {
            return false;
        }
        boolean changed = !deleted
                || !Objects.equals(sourceUpdatedAt, validatedSourceUpdatedAt)
                || !sameRawRecord(rawRecordVersion, validatedRawRecord);
        deleted = true;
        sourceUpdatedAt = validatedSourceUpdatedAt;
        rawRecordVersion = validatedRawRecord;
        lastSyncRun = validatedRun;
        return changed;
    }


    public IntegrationConnection getConnection() {
        return connection;
    }

    public String getExternalId() {
        return externalId;
    }

    public Store getStore() {
        return store;
    }

    public Employee getEmployee() {
        return employee;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getSourceUpdatedAt() {
        return sourceUpdatedAt;
    }

    public String getSourceStatus() {
        return sourceStatus;
    }

    public boolean isDeleted() {
        return deleted;
    }

    private boolean sameRawRecord(RawRecordVersion first, RawRecordVersion second) {
        return first == second
                || first != null
                && second != null
                && first.getId() != null
                && first.getId().equals(second.getId());
    }

    private boolean sameAmount(BigDecimal first, BigDecimal second) {
        return first == null ? second == null : second != null && first.compareTo(second) == 0;
    }

    private boolean sameConnection(
            IntegrationConnection first,
            IntegrationConnection second
    ) {
        return first == second
                || first != null
                && second != null
                && first.getId() != null
                && first.getId().equals(second.getId());
    }

    private boolean sameStore(Store first, Store second) {
        return first == second
                || first != null
                && second != null
                && first.getId() != null
                && first.getId().equals(second.getId());
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
