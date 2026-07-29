package com.storeanalytics.sync.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.storeanalytics.employee.model.Employee;
import com.storeanalytics.integration.livesklad.dto.LiveSkladSaleDetailPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladSalePositionPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladSaleSummaryPayload;
import com.storeanalytics.product.model.AnalyticsCategory;
import com.storeanalytics.product.model.Product;
import com.storeanalytics.product.model.ProductCategoryAssignment;
import com.storeanalytics.product.model.ProductConditionType;
import com.storeanalytics.product.model.ProductDetails;
import com.storeanalytics.product.model.ProductSourceKind;
import com.storeanalytics.product.service.LiveSkladProductIdentityResolver;
import com.storeanalytics.product.service.LiveSkladProductIdentityResolver.ResolutionKind;
import com.storeanalytics.quality.model.DataQualityIssue;
import com.storeanalytics.quality.model.DataQualitySeverity;
import com.storeanalytics.quality.model.DataQualityStatus;
import com.storeanalytics.sales.model.CostQuality;
import com.storeanalytics.sales.model.PaymentMethod;
import com.storeanalytics.sales.model.SalesDocument;
import com.storeanalytics.sales.model.SalesDocumentAmounts;
import com.storeanalytics.sales.model.SalesDocumentDetails;
import com.storeanalytics.sales.model.SalesDocumentIdentity;
import com.storeanalytics.sales.model.SalesDocumentItem;
import com.storeanalytics.sales.model.SalesDocumentKind;
import com.storeanalytics.sales.model.SalesItemAmounts;
import com.storeanalytics.sales.model.SalesItemClassification;
import com.storeanalytics.sales.model.SalesItemIdentity;
import com.storeanalytics.sales.model.SalesPayment;
import com.storeanalytics.sync.model.RawRecordVersion;
import com.storeanalytics.sync.model.SourceSystem;
import com.storeanalytics.sync.model.SyncRun;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.sync.support.JsonPayloadHasher;
import com.storeanalytics.sync.support.PreparedRawPayload;
import com.storeanalytics.sync.support.RawPayloadProfile;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SalesSyncPersistence {

    private static final String SALE_ENTITY_TYPE = "SALE_DOCUMENT";
    private static final String UNMAPPED_CATEGORY_CODE = "UNMAPPED";
    private static final String UNMAPPED_CLASSIFICATION_VERSION = "unmapped-v1";

    private final SalesReferenceRepositories referenceRepositories;
    private final SalesFactRepositories factRepositories;
    private final LiveSkladProductIdentityResolver identityResolver;
    private final JsonPayloadHasher payloadHasher;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ZoneId businessZone;

    public SalesSyncPersistence(
            SalesReferenceRepositories referenceRepositories,
            SalesFactRepositories factRepositories,
            LiveSkladProductIdentityResolver identityResolver,
            JsonPayloadHasher payloadHasher,
            ObjectMapper objectMapper,
            Clock clock,
            ZoneId businessZone
    ) {
        this.referenceRepositories = referenceRepositories;
        this.factRepositories = factRepositories;
        this.identityResolver = identityResolver;
        this.payloadHasher = payloadHasher;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.businessZone = businessZone;
    }

    @Transactional
    SalesSyncBatchResult synchronize(
            UUID syncRunId,
            SalesSyncPeriod period,
            List<StoreSalesBatch> batches
    ) {
        SyncRun syncRun = factRepositories.syncRuns().findById(syncRunId)
                .orElseThrow(() -> new IllegalArgumentException("syncRun does not exist"));
        AnalyticsCategory unmappedCategory = referenceRepositories.categories()
                .findByCode(UNMAPPED_CATEGORY_CODE)
                .orElseThrow(() -> new IllegalStateException(
                        "UNMAPPED analytics category is not configured"
                ));
        Instant now = clock.instant();
        Accumulator result = new Accumulator();
        Map<String, Product> productCache = new HashMap<>();
        SyncContext context = new SyncContext(
                syncRun,
                period,
                unmappedCategory,
                productCache,
                result,
                now
        );

        for (StoreSalesBatch batch : List.copyOf(batches)) {
            Store store = referenceRepositories.stores().findById(batch.store().getId())
                    .orElseThrow(() -> new IllegalArgumentException("store does not exist"));
            validateStore(syncRun, store);
            Set<String> seenDocumentIds = new HashSet<>();
            for (LiveSkladSaleSource source : batch.sales()) {
                if (!seenDocumentIds.add(source.summary().externalId())) {
                    throw new IllegalArgumentException(
                            "sales batch contains duplicate document IDs"
                    );
                }
                synchronizeDocument(context, store, source);
            }
            markMissingDocumentsDeleted(
                    syncRun,
                    store,
                    period,
                    seenDocumentIds,
                    result
            );
        }
        return result.toResult();
    }

    private void synchronizeDocument(
            SyncContext context,
            Store store,
            LiveSkladSaleSource source
    ) {
        SyncRun syncRun = context.syncRun();
        SalesSyncPeriod period = context.period();
        AnalyticsCategory unmappedCategory = context.unmappedCategory();
        Map<String, Product> productCache = context.productCache();
        Accumulator result = context.result();
        Instant now = context.now();
        LiveSkladSaleSummaryPayload summary = source.summary();
        LiveSkladSaleDetailPayload detail = source.detail();
        validateSource(store, period, summary, detail);

        ObjectNode combinedPayload = objectMapper.createObjectNode();
        combinedPayload.set("list", summary.rawPayload().deepCopy());
        combinedPayload.set("detail", detail.rawPayload().deepCopy());
        PreparedRawPayload preparedPayload = payloadHasher.prepare(RawPayloadProfile.SALE_DOCUMENT, combinedPayload);
        String hash = preparedPayload.sha256();
        Instant sourceUpdatedAt = detail.sourceUpdatedAt() == null
                ? detail.occurredAt() : detail.sourceUpdatedAt();
        RawRecordVersion rawVersion = factRepositories.rawRecords().findStoreRecordVersion(
                syncRun.getConnection().getId(),
                store.getId(),
                SourceSystem.LIVESKLAD.name(),
                SALE_ENTITY_TYPE,
                summary.externalId(),
                hash
        ).map(existing -> {
            existing.markSeenWithRetainedPayload(
                    syncRun,
                    now,
                    preparedPayload.json()
            );
            return existing;
        }).orElseGet(() -> factRepositories.rawRecords().save(
                RawRecordVersion.pendingSale(
                        store,
                        summary.externalId(),
                        preparedPayload.json(),
                        hash,
                        sourceUpdatedAt,
                        syncRun,
                        now
                )
        ));

        Optional<SalesDocument> existingDocument = factRepositories.documents()
                .findByConnectionIdAndExternalId(
                        syncRun.getConnection().getId(),
                        summary.externalId()
                );
        boolean sourceVersionAccepted = existingDocument
                .map(document -> document.acceptsSourceVersion(sourceUpdatedAt))
                .orElse(true);
        Employee employee = resolveEmployee(syncRun, detail);
        SalesDocumentDetails documentDetails = new SalesDocumentDetails(
                summary.documentNumber(),
                SalesDocumentKind.SALE,
                summary.sourceType(),
                null,
                summary.occurredAt(),
                summary.occurredAt().atZone(businessZone).toLocalDate(),
                sourceUpdatedAt
        );
        SalesDocumentAmounts documentAmounts = new SalesDocumentAmounts(
                summary.netAmount(),
                summary.costAmount()
        );
        SalesDocumentIdentity identity = new SalesDocumentIdentity(
                syncRun.getConnection(),
                SourceSystem.LIVESKLAD,
                summary.externalId(),
                store,
                employee,
                null,
                syncRun
        );

        SalesDocument document;
        boolean documentCreated = existingDocument.isEmpty();
        boolean documentChanged;
        if (documentCreated) {
            document = factRepositories.documents().save(new SalesDocument(
                    identity,
                    documentDetails,
                    documentAmounts,
                    rawVersion
            ));
            documentChanged = true;
        } else {
            document = existingDocument.get();
            documentChanged = document.updateFromLiveSklad(
                    identity,
                    documentDetails,
                    documentAmounts,
                    rawVersion
            );
        }

        if (sourceVersionAccepted) {
            boolean factsChanged = synchronizeItems(context, store, document, detail);
            factsChanged |= synchronizePayments(document, detail, result);
            reconcileDocument(syncRun, store, summary, detail, result, now);
            documentChanged |= factsChanged;
        }

        if (!rawVersion.isNormalized()) {
            rawVersion.markNormalized(now);
        }
        if (documentCreated) {
            result.documentsCreated++;
        } else if (documentChanged) {
            result.documentsUpdated++;
        } else {
            result.documentsSkipped++;
        }
    }

    private boolean synchronizeItems(
            SyncContext context,
            Store store,
            SalesDocument document,
            LiveSkladSaleDetailPayload detail
    ) {
        SyncRun syncRun = context.syncRun();
        AnalyticsCategory unmappedCategory = context.unmappedCategory();
        Map<String, Product> productCache = context.productCache();
        Accumulator result = context.result();
        Instant now = context.now();
        Map<String, SalesDocumentItem> existingItems = new LinkedHashMap<>();
        for (SalesDocumentItem item : factRepositories.items()
                .findAllBySalesDocumentId(document.getId())) {
            existingItems.put(item.getExternalId(), item);
        }

        boolean changed = false;
        Set<String> seenItemIds = new HashSet<>();
        for (LiveSkladSalePositionPayload sourceItem : detail.positions()) {
            if (!seenItemIds.add(sourceItem.externalId())) {
                throw new IllegalArgumentException(
                        "sale contains duplicate position IDs"
                );
            }
            Product product = resolveProduct(
                    syncRun,
                    sourceItem,
                    detail.occurredAt(),
                    productCache,
                    result
            );
            Classification classification = classify(
                    product,
                    detail.occurredAt(),
                    unmappedCategory
            );
            synchronizeIssue(
                    classification.assignment() == null,
                    store,
                    qualityIssue(
                            "PRODUCT",
                            scopedId(syncRun, sourceItem.productExternalId()),
                            "UNMAPPED_PRODUCT",
                            DataQualitySeverity.WARNING,
                            "Product has no effective analytics classification"
                    ),
                    now,
                    result
            );

            SalesItemAmounts amounts = itemAmounts(sourceItem);
            CostQuality costQuality = costQuality(sourceItem);
            synchronizeIssue(
                    costQuality == CostQuality.ZERO_UNEXPECTED,
                    store,
                    qualityIssue(
                            "SALE_ITEM",
                            scopedId(syncRun, sourceItem.externalId()),
                            "ZERO_UNEXPECTED_COST",
                            DataQualitySeverity.WARNING,
                            "Non-service sale item has zero cost"
                    ),
                    now,
                    result
            );
            synchronizeIssue(
                    costQuality == CostQuality.MISSING,
                    store,
                    qualityIssue(
                            "SALE_ITEM",
                            scopedId(syncRun, sourceItem.externalId()),
                            "MISSING_COST",
                            DataQualitySeverity.WARNING,
                            "Sale item cost is missing"
                    ),
                    now,
                    result
            );

            SalesItemClassification itemClassification = new SalesItemClassification(
                    product.getName(),
                    null,
                    classification.category(),
                    classification.assignment(),
                    classification.version(),
                    classification.conditionType()
            );
            SalesDocumentItem existing = existingItems.get(sourceItem.externalId());
            if (existing == null) {
                factRepositories.items().save(new SalesDocumentItem(
                        new SalesItemIdentity(
                                document,
                                sourceItem.externalId(),
                                null,
                                product
                        ),
                        itemClassification,
                        amounts,
                        costQuality,
                        sourceItem.work()
                ));
                result.itemsCreated++;
                changed = true;
            } else if (existing.update(
                    product,
                    itemClassification,
                    amounts,
                    costQuality,
                    sourceItem.work()
            )) {
                result.itemsUpdated++;
                changed = true;
            }
        }

        for (SalesDocumentItem existing : existingItems.values()) {
            if (!seenItemIds.contains(existing.getExternalId())
                    && existing.markDeleted()) {
                result.itemsDeleted++;
                changed = true;
            }
        }
        return changed;
    }

    private Product resolveProduct(
            SyncRun syncRun,
            LiveSkladSalePositionPayload source,
            Instant observedAt,
            Map<String, Product> cache,
            Accumulator result
    ) {
        Product cached = cache.get(source.productExternalId());
        if (cached != null) {
            if (cached.updateFromLiveSklad(productDetails(source, observedAt))) {
                result.productsUpdated++;
            }
            return cached;
        }

        LiveSkladProductIdentityResolver.ProductIdentityResolution resolution =
                identityResolver.resolveObservedProduct(
                    syncRun.getConnection(),
                    source.productExternalId(),
                    productDetails(source, observedAt)
                );
        Product product = resolution.product();
        if (resolution.kind() == ResolutionKind.CREATED) {
            result.productsCreated++;
        } else if (resolution.kind() == ResolutionKind.UPDATED) {
            result.productsUpdated++;
        }
        cache.put(source.productExternalId(), product);
        return product;
    }

    private ProductDetails productDetails(
            LiveSkladSalePositionPayload source,
            Instant observedAt
    ) {
        return new ProductDetails(
                null,
                source.code(),
                source.sku(),
                source.name(),
                source.work() ? ProductSourceKind.SERVICE : ProductSourceKind.PRODUCT,
                observedAt
        );
    }

    private Classification classify(
            Product product,
            Instant occurredAt,
            AnalyticsCategory unmappedCategory
    ) {
        List<ProductCategoryAssignment> assignments =
                referenceRepositories.assignments().findEffectiveAssignments(
                        product.getId(),
                        occurredAt
                );
        if (assignments.isEmpty()) {
            return new Classification(
                    unmappedCategory,
                    null,
                    UNMAPPED_CLASSIFICATION_VERSION,
                    ProductConditionType.UNKNOWN
            );
        }
        ProductCategoryAssignment assignment = assignments.getFirst();
        String version = assignment.getRuleVersion() == null
                ? "assignment:" + assignment.getId()
                : assignment.getRuleVersion();
        return new Classification(
                assignment.getAnalyticsCategory(),
                assignment,
                version,
                assignment.getConditionType()
        );
    }

    private SalesItemAmounts itemAmounts(LiveSkladSalePositionPayload source) {
        BigDecimal grossAmount = multiplyMoney(
                source.unitListPrice(),
                source.quantity(),
                "grossAmount"
        );
        BigDecimal netAmount = multiplyMoney(
                source.unitSoldPrice(),
                source.quantity(),
                "netAmount"
        );
        BigDecimal discountAmount = grossAmount.subtract(netAmount);
        if (discountAmount.signum() < 0) {
            throw new IllegalArgumentException(
                    "sale item sold price must not exceed list price"
            );
        }
        return new SalesItemAmounts(
                source.quantity(),
                source.unitListPrice(),
                grossAmount,
                discountAmount,
                netAmount,
                source.costAmount()
        );
    }

    private BigDecimal multiplyMoney(
            BigDecimal unitAmount,
            BigDecimal quantity,
            String fieldName
    ) {
        try {
            return unitAmount.multiply(quantity)
                    .setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    fieldName + " cannot be represented as numeric(19, 2)",
                    exception
            );
        }
    }

    private CostQuality costQuality(LiveSkladSalePositionPayload source) {
        if (source.costAmount() == null) {
            return CostQuality.MISSING;
        }
        if (source.costAmount().signum() != 0) {
            return CostQuality.KNOWN;
        }
        return source.work()
                ? CostQuality.ZERO_SERVICE
                : CostQuality.ZERO_UNEXPECTED;
    }

    private boolean synchronizePayments(
            SalesDocument document,
            LiveSkladSaleDetailPayload detail,
            Accumulator result
    ) {
        Map<String, SalesPayment> existingPayments = new LinkedHashMap<>();
        for (SalesPayment payment : factRepositories.payments()
                .findAllBySalesDocumentId(document.getId())) {
            existingPayments.put(payment.getExternalId(), payment);
        }

        List<PaymentComponent> incoming = new ArrayList<>();
        addPayment(
                incoming,
                document.getExternalId() + ":cash",
                PaymentMethod.CASH,
                detail.cashAmount()
        );
        addPayment(
                incoming,
                document.getExternalId() + ":card",
                PaymentMethod.CARD,
                detail.cardAmount()
        );
        addPayment(
                incoming,
                document.getExternalId() + ":bank-transfer",
                PaymentMethod.BANK_TRANSFER,
                detail.bankTransferAmount()
        );

        boolean changed = false;
        Set<String> seenPaymentIds = new HashSet<>();
        for (PaymentComponent component : incoming) {
            seenPaymentIds.add(component.externalId());
            SalesPayment existing = existingPayments.get(component.externalId());
            if (existing == null) {
                factRepositories.payments().save(new SalesPayment(
                        document,
                        component.externalId(),
                        component.method(),
                        component.amount(),
                        detail.occurredAt()
                ));
                result.paymentsCreated++;
                changed = true;
            } else if (existing.update(
                    component.method(),
                    component.amount(),
                    detail.occurredAt()
            )) {
                result.paymentsUpdated++;
                changed = true;
            }
        }
        for (SalesPayment existing : existingPayments.values()) {
            if (!seenPaymentIds.contains(existing.getExternalId())
                    && existing.markDeleted()) {
                result.paymentsDeleted++;
                changed = true;
            }
        }
        return changed;
    }

    private void addPayment(
            List<PaymentComponent> payments,
            String externalId,
            PaymentMethod method,
            BigDecimal amount
    ) {
        if (amount.signum() > 0) {
            payments.add(new PaymentComponent(externalId, method, amount));
        }
    }

    private void reconcileDocument(
            SyncRun syncRun,
            Store store,
            LiveSkladSaleSummaryPayload summary,
            LiveSkladSaleDetailPayload detail,
            Accumulator result,
            Instant now
    ) {
        BigDecimal itemGross = BigDecimal.ZERO.setScale(2);
        BigDecimal itemNet = BigDecimal.ZERO.setScale(2);
        BigDecimal itemCost = BigDecimal.ZERO.setScale(2);
        boolean completeCost = true;
        for (LiveSkladSalePositionPayload item : detail.positions()) {
            SalesItemAmounts amounts = itemAmounts(item);
            itemGross = itemGross.add(amounts.grossAmount());
            itemNet = itemNet.add(amounts.netAmount());
            if (amounts.costAmount() == null) {
                completeCost = false;
            } else {
                itemCost = itemCost.add(amounts.costAmount());
            }
        }
        BigDecimal paymentTotal = detail.cashAmount()
                .add(detail.cardAmount())
                .add(detail.bankTransferAmount());
        String entityId = scopedId(syncRun, summary.externalId());

        synchronizeIssue(
                itemGross.compareTo(summary.grossAmount()) != 0,
                store,
                qualityIssue(
                        SALE_ENTITY_TYPE,
                        entityId,
                        "SALE_ITEM_GROSS_MISMATCH",
                        DataQualitySeverity.ERROR,
                        "Sale gross amount does not match its active items"
                ),
                now,
                result
        );
        synchronizeIssue(
                itemNet.compareTo(summary.netAmount()) != 0,
                store,
                qualityIssue(
                        SALE_ENTITY_TYPE,
                        entityId,
                        "SALE_ITEM_NET_MISMATCH",
                        DataQualitySeverity.ERROR,
                        "Sale net amount does not match its active items"
                ),
                now,
                result
        );
        synchronizeIssue(
                summary.costAmount() != null
                        && completeCost
                        && itemCost.compareTo(summary.costAmount()) != 0,
                store,
                qualityIssue(
                        SALE_ENTITY_TYPE,
                        entityId,
                        "SALE_ITEM_COST_MISMATCH",
                        DataQualitySeverity.WARNING,
                        "Sale cost does not match its active items"
                ),
                now,
                result
        );
        synchronizeIssue(
                paymentTotal.compareTo(summary.netAmount()) != 0,
                store,
                qualityIssue(
                        SALE_ENTITY_TYPE,
                        entityId,
                        "SALE_PAYMENT_MISMATCH",
                        DataQualitySeverity.WARNING,
                        "Sale payment total does not match document net amount"
                ),
                now,
                result
        );
    }

    private void synchronizeIssue(
            boolean present,
            Store store,
            QualityIssueSpec issue,
            Instant now,
            Accumulator result
    ) {
        Optional<DataQualityIssue> existing = factRepositories.qualityIssues()
                .findByEntityTypeAndEntityIdAndIssueCodeAndStatus(
                        issue.entityType(),
                        issue.entityId(),
                        issue.issueCode(),
                        DataQualityStatus.OPEN
                );
        if (present && existing.isEmpty()) {
            factRepositories.qualityIssues().save(DataQualityIssue.open(
                    store,
                    issue.entityType(),
                    issue.entityId(),
                    issue.issueCode(),
                    issue.severity(),
                    issue.message(),
                    now
            ));
            result.qualityIssuesOpened++;
        } else if (!present && existing.isPresent()) {
            existing.get().resolve(null, now);
            result.qualityIssuesResolved++;
        }
    }

    private Employee resolveEmployee(
            SyncRun syncRun,
            LiveSkladSaleDetailPayload detail
    ) {
        if (detail.employeeExternalId() == null) {
            return null;
        }
        return referenceRepositories.employees().findByConnectionIdAndExternalId(
                syncRun.getConnection().getId(),
                detail.employeeExternalId()
        ).orElseThrow(() -> new IllegalStateException(
                "Sale references an unknown employee; synchronize employees first"
        ));
    }

    private void markMissingDocumentsDeleted(
            SyncRun syncRun,
            Store store,
            SalesSyncPeriod period,
            Set<String> seenDocumentIds,
            Accumulator result
    ) {
        for (SalesDocument document : factRepositories.documents()
                .findAllByConnectionIdAndStoreIdAndDocumentKindAndOccurredAtBetween(
                        syncRun.getConnection().getId(),
                        store.getId(),
                        SalesDocumentKind.SALE,
                        period.start(),
                        period.end()
                )) {
            if (!seenDocumentIds.contains(document.getExternalId())
                    && document.markDeleted(syncRun)) {
                result.documentsDeleted++;
            }
        }
    }

    private void validateSource(
            Store store,
            SalesSyncPeriod period,
            LiveSkladSaleSummaryPayload summary,
            LiveSkladSaleDetailPayload detail
    ) {
        if (!summary.externalId().equals(detail.externalId())
                || !summary.occurredAt().equals(detail.occurredAt())
                || !equalsNullable(summary.documentNumber(), detail.documentNumber())
                || !summary.sourceType().equalsIgnoreCase("sale")
                || !detail.sourceType().equalsIgnoreCase("sale")
                || !store.getExternalId().equals(detail.storeExternalId())) {
            throw new IllegalArgumentException(
                    "LiveSklad sale list and detail are inconsistent"
            );
        }
        if (summary.occurredAt().isBefore(period.start())
                || summary.occurredAt().isAfter(period.end())) {
            throw new IllegalArgumentException(
                    "LiveSklad sale is outside the requested period"
            );
        }
        if (summary.rawPayload() == null || detail.rawPayload() == null) {
            throw new IllegalArgumentException("LiveSklad sale raw payload is missing");
        }
    }

    private void validateStore(SyncRun syncRun, Store store) {
        if (!store.isConnectedTo(syncRun.getConnection())) {
            throw new IllegalArgumentException(
                    "store and sales sync run must belong to the same connection"
            );
        }
    }

    private boolean equalsNullable(String first, String second) {
        return first == null ? second == null : first.equals(second);
    }

    private String scopedId(SyncRun syncRun, String externalId) {
        return syncRun.getConnection().getId() + ":" + externalId;
    }

    private QualityIssueSpec qualityIssue(
            String entityType,
            String entityId,
            String issueCode,
            DataQualitySeverity severity,
            String message
    ) {
        return new QualityIssueSpec(
                entityType,
                entityId,
                issueCode,
                severity,
                message
        );
    }

    private record SyncContext(
            SyncRun syncRun,
            SalesSyncPeriod period,
            AnalyticsCategory unmappedCategory,
            Map<String, Product> productCache,
            Accumulator result,
            Instant now
    ) {
    }

    private record QualityIssueSpec(
            String entityType,
            String entityId,
            String issueCode,
            DataQualitySeverity severity,
            String message
    ) {
    }

    private record Classification(
            AnalyticsCategory category,
            ProductCategoryAssignment assignment,
            String version,
            ProductConditionType conditionType
    ) {
    }

    private record PaymentComponent(
            String externalId,
            PaymentMethod method,
            BigDecimal amount
    ) {
    }

    private static final class Accumulator {

        private int documentsCreated;
        private int documentsUpdated;
        private int documentsSkipped;
        private int documentsDeleted;
        private int productsCreated;
        private int productsUpdated;
        private int itemsCreated;
        private int itemsUpdated;
        private int itemsDeleted;
        private int paymentsCreated;
        private int paymentsUpdated;
        private int paymentsDeleted;
        private int qualityIssuesOpened;
        private int qualityIssuesResolved;

        private SalesSyncBatchResult toResult() {
            return new SalesSyncBatchResult(
                    documentsCreated,
                    documentsUpdated,
                    documentsSkipped,
                    documentsDeleted,
                    productsCreated,
                    productsUpdated,
                    itemsCreated,
                    itemsUpdated,
                    itemsDeleted,
                    paymentsCreated,
                    paymentsUpdated,
                    paymentsDeleted,
                    qualityIssuesOpened,
                    qualityIssuesResolved
            );
        }
    }
}
