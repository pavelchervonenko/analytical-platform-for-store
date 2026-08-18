package com.storeanalytics.sync.service;

import com.storeanalytics.employee.model.Employee;
import com.storeanalytics.integration.livesklad.dto.LiveSkladOrderDetailPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladOrderPositionPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladOrderSummaryPayload;
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
import com.storeanalytics.sales.model.SalesDocument;
import com.storeanalytics.sales.model.SalesDocumentAmounts;
import com.storeanalytics.sales.model.SalesDocumentDetails;
import com.storeanalytics.sales.model.SalesDocumentIdentity;
import com.storeanalytics.sales.model.SalesDocumentItem;
import com.storeanalytics.sales.model.SalesDocumentKind;
import com.storeanalytics.sales.model.SalesItemAmounts;
import com.storeanalytics.sales.model.SalesItemClassification;
import com.storeanalytics.sales.model.SalesItemIdentity;
import com.storeanalytics.sync.model.RawRecordDescriptor;
import com.storeanalytics.sync.model.RawRecordVersion;
import com.storeanalytics.sync.model.SourceSystem;
import com.storeanalytics.sync.model.SyncRun;
import com.storeanalytics.sync.support.JsonPayloadHasher;
import com.storeanalytics.sync.support.PreparedRawPayload;
import com.storeanalytics.sync.support.RawPayloadProfile;
import com.storeanalytics.store.model.Store;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class OrderSyncPersistence {

    static final String SOURCE_DOCUMENT_TYPE = "orderPosition";
    private static final String RAW_ENTITY_TYPE = "ORDER_POSITION";
    private static final String UNMAPPED_CATEGORY_CODE = "UNMAPPED";
    private static final String UNMAPPED_CLASSIFICATION_VERSION = "unmapped-v1";
    private static final String ISSUED_STATUS = "ВЫДАН";

    private final SalesReferenceRepositories referenceRepositories;
    private final SalesFactRepositories factRepositories;
    private final LiveSkladProductIdentityResolver identityResolver;
    private final JsonPayloadHasher payloadHasher;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;
    private final Clock clock;
    private final ZoneId businessZone;

    public OrderSyncPersistence(
            SalesReferenceRepositories referenceRepositories,
            SalesFactRepositories factRepositories,
            LiveSkladProductIdentityResolver identityResolver,
            JsonPayloadHasher payloadHasher,
            OrderSyncInfrastructure infrastructure
    ) {
        this.referenceRepositories = referenceRepositories;
        this.factRepositories = factRepositories;
        this.identityResolver = identityResolver;
        this.payloadHasher = payloadHasher;
        this.objectMapper = infrastructure.objectMapper();
        this.entityManager = infrastructure.entityManager();
        this.clock = infrastructure.clock();
        this.businessZone = infrastructure.businessZone();
    }

    @Transactional
    OrderSyncBatchResult synchronize(
            UUID syncRunId,
            OrderSyncPeriod period,
            List<StoreOrderBatch> batches
    ) {
        SyncRun syncRun = factRepositories.syncRuns().findById(syncRunId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "syncRun does not exist"
                ));
        AnalyticsCategory unmappedCategory = referenceRepositories.categories()
                .findByCode(UNMAPPED_CATEGORY_CODE)
                .orElseThrow(() -> new IllegalStateException(
                        "UNMAPPED analytics category is not configured"
                ));
        Accumulator result = new Accumulator();
        SyncContext context = new SyncContext(
                syncRun,
                unmappedCategory,
                new HashMap<>(),
                result,
                clock.instant()
        );
        Set<String> seenOrderIds = new HashSet<>();
        for (StoreOrderBatch batch : List.copyOf(batches)) {
            Store store = referenceRepositories.stores()
                    .findById(batch.store().getId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "store does not exist"
                    ));
            validateStore(syncRun, store);
            for (LiveSkladOrderSource source : batch.orders()) {
                if (!seenOrderIds.add(source.summary().externalId())) {
                    throw new IllegalArgumentException(
                            "order batch contains duplicate order IDs"
                    );
                }
                synchronizeOrder(context, store, source);
            }
        }
        return result.toResult();
    }

    private void synchronizeOrder(
            SyncContext context,
            Store store,
            LiveSkladOrderSource source
    ) {
        validateSource(store, source);
        LiveSkladOrderDetailPayload detail = source.detail();
        Set<String> seenDocumentIds = new HashSet<>();
        if (isIssued(source.summary(), detail)) {
            for (LiveSkladOrderPositionPayload position : detail.positions()) {
                String documentExternalId = documentExternalId(
                        detail.externalId(),
                        position.externalId()
                );
                if (!seenDocumentIds.add(documentExternalId)) {
                    throw new IllegalArgumentException(
                            "order contains duplicate normalized position IDs"
                    );
                }
                synchronizePosition(
                        context,
                        store,
                        source,
                        position,
                        documentExternalId
                );
            }
        }
        markMissingPositionsDeleted(
                context,
                detail.externalId(),
                detail.sourceUpdatedAt(),
                seenDocumentIds
        );
    }

    private void synchronizePosition(
            SyncContext context,
            Store store,
            LiveSkladOrderSource source,
            LiveSkladOrderPositionPayload position,
            String documentExternalId
    ) {
        SyncRun syncRun = context.syncRun();
        LiveSkladOrderDetailPayload detail = source.detail();
        PreparedRawPayload preparedPayload = payloadHasher.prepare(
                RawPayloadProfile.SALE_DOCUMENT,
                retainedPayload(source, position, documentExternalId)
        );
        RawRecordVersion rawVersion = factRepositories.rawRecords()
                .findStoreRecordVersion(
                        syncRun.getConnection().getId(),
                        store.getId(),
                        SourceSystem.LIVESKLAD.name(),
                        RAW_ENTITY_TYPE,
                        documentExternalId,
                        preparedPayload.sha256()
                ).map(existing -> {
                    existing.markSeenWithRetainedPayload(
                            syncRun,
                            context.now(),
                            preparedPayload.json()
                    );
                    return existing;
                }).orElseGet(() -> factRepositories.rawRecords().save(
                        RawRecordVersion.pending(
                                new RawRecordDescriptor(
                                        syncRun.getConnection(),
                                        store,
                                        SourceSystem.LIVESKLAD,
                                        RAW_ENTITY_TYPE,
                                        documentExternalId,
                                        detail.sourceUpdatedAt()
                                ),
                                preparedPayload.json(),
                                preparedPayload.sha256(),
                                syncRun,
                                context.now()
                        )
                ));

        Optional<SalesDocument> existingDocument = factRepositories.documents()
                .findByConnectionIdAndExternalId(
                        syncRun.getConnection().getId(),
                        documentExternalId
                );
        boolean sourceVersionAccepted = existingDocument
                .map(document -> document.acceptsSourceVersion(
                        detail.sourceUpdatedAt()
                )).orElse(true);
        Employee employee = resolveEmployee(syncRun, position);
        SalesItemAmounts itemAmounts = itemAmounts(position);
        SalesDocumentDetails documentDetails = new SalesDocumentDetails(
                detail.documentNumber(),
                SalesDocumentKind.SALE,
                SOURCE_DOCUMENT_TYPE,
                detail.statusName(),
                position.occurredAt(),
                position.occurredAt().atZone(businessZone).toLocalDate(),
                detail.sourceUpdatedAt()
        );
        SalesDocumentIdentity identity = new SalesDocumentIdentity(
                syncRun.getConnection(),
                SourceSystem.LIVESKLAD,
                documentExternalId,
                store,
                employee,
                null,
                syncRun
        );
        SalesDocumentAmounts documentAmounts = new SalesDocumentAmounts(
                itemAmounts.netAmount(),
                itemAmounts.costAmount()
        );

        SalesDocument document;
        boolean created = existingDocument.isEmpty();
        boolean changed;
        if (created) {
            document = factRepositories.documents().save(new SalesDocument(
                    identity,
                    documentDetails,
                    documentAmounts,
                    rawVersion
            ));
            changed = true;
        } else {
            document = existingDocument.orElseThrow();
            changed = document.updateFromLiveSklad(
                    identity,
                    documentDetails,
                    documentAmounts,
                    rawVersion
            );
        }
        if (sourceVersionAccepted) {
            changed |= synchronizeItem(
                    context,
                    store,
                    document,
                    position,
                    itemAmounts
            );
            changed |= removePayments(document, context.result());
        }
        if (!rawVersion.isNormalized()) {
            rawVersion.markNormalized(context.now());
        }
        if (created) {
            context.result().documentsCreated++;
        } else if (changed) {
            context.result().documentsUpdated++;
        } else {
            context.result().documentsSkipped++;
        }
    }

    private boolean synchronizeItem(
            SyncContext context,
            Store store,
            SalesDocument document,
            LiveSkladOrderPositionPayload source,
            SalesItemAmounts amounts
    ) {
        Map<String, SalesDocumentItem> existingItems = new LinkedHashMap<>();
        for (SalesDocumentItem item : factRepositories.items()
                .findAllBySalesDocumentId(document.getId())) {
            existingItems.put(item.getExternalId(), item);
        }
        Product product = resolveProduct(context, source);
        Classification classification = classify(
                product,
                source.occurredAt(),
                context.unmappedCategory()
        );
        synchronizeIssue(
                classification.category() == context.unmappedCategory(),
                store,
                qualityIssue(
                        "PRODUCT",
                        scopedId(context.syncRun(), source.productExternalId()),
                        "UNMAPPED_PRODUCT",
                        DataQualitySeverity.WARNING,
                        "Product has no effective analytics classification"
                ),
                context
        );
        CostQuality costQuality = costQuality(source, classification.category());
        String itemEntityId = scopedId(
                context.syncRun(),
                document.getExternalId() + ":" + source.externalId()
        );
        synchronizeIssue(
                costQuality == CostQuality.ZERO_UNEXPECTED,
                store,
                qualityIssue(
                        "ORDER_ITEM",
                        itemEntityId,
                        "ZERO_UNEXPECTED_COST",
                        DataQualitySeverity.WARNING,
                        "Non-service order item has zero cost"
                ),
                context
        );
        synchronizeIssue(
                costQuality == CostQuality.MISSING,
                store,
                qualityIssue(
                        "ORDER_ITEM",
                        itemEntityId,
                        "MISSING_COST",
                        DataQualitySeverity.WARNING,
                        "Order item cost is missing"
                ),
                context
        );
        SalesItemClassification itemClassification =
                new SalesItemClassification(
                        product.getName(),
                        null,
                        classification.category(),
                        classification.assignment(),
                        classification.version(),
                        classification.conditionType()
                );
        boolean changed = false;
        SalesDocumentItem existing = existingItems.remove(source.externalId());
        if (existing == null) {
            factRepositories.items().save(new SalesDocumentItem(
                    new SalesItemIdentity(
                            document,
                            source.externalId(),
                            null,
                            product
                    ),
                    itemClassification,
                    amounts,
                    costQuality,
                    source.work()
            ));
            context.result().itemsCreated++;
            changed = true;
        } else if (existing.update(
                product,
                itemClassification,
                amounts,
                costQuality,
                source.work()
        )) {
            context.result().itemsUpdated++;
            changed = true;
        }
        for (SalesDocumentItem unexpected : existingItems.values()) {
            if (unexpected.markDeleted()) {
                context.result().itemsDeleted++;
                changed = true;
            }
        }
        return changed;
    }

    private Product resolveProduct(
            SyncContext context,
            LiveSkladOrderPositionPayload source
    ) {
        Product cached = context.productCache().get(source.productExternalId());
        ProductDetails details = productDetails(source);
        if (cached != null) {
            if (cached.updateFromLiveSklad(details)) {
                context.result().productsUpdated++;
            }
            return cached;
        }
        LiveSkladProductIdentityResolver.ProductIdentityResolution resolution =
                identityResolver.resolveObservedProduct(
                        context.syncRun().getConnection(),
                        source.productExternalId(),
                        details
                );
        Product product = resolution.product();
        if (resolution.kind() == ResolutionKind.CREATED) {
            context.result().productsCreated++;
        } else if (resolution.kind() == ResolutionKind.UPDATED) {
            context.result().productsUpdated++;
        }
        context.productCache().put(source.productExternalId(), product);
        return product;
    }

    private ProductDetails productDetails(
            LiveSkladOrderPositionPayload source
    ) {
        return new ProductDetails(
                null,
                source.code(),
                source.sku(),
                source.name(),
                source.work()
                        ? ProductSourceKind.SERVICE
                        : ProductSourceKind.PRODUCT,
                source.occurredAt()
        );
    }

    private Classification classify(
            Product product,
            Instant occurredAt,
            AnalyticsCategory unmappedCategory
    ) {
        var resolved = referenceRepositories.classificationResolver()
                .resolve(product, occurredAt);
        if (resolved.isEmpty()) {
            return new Classification(
                    unmappedCategory,
                    null,
                    UNMAPPED_CLASSIFICATION_VERSION,
                    ProductConditionType.UNKNOWN
            );
        }
        var classification = resolved.orElseThrow();
        return new Classification(
                classification.category(),
                classification.assignment(),
                classification.version(),
                classification.conditionType()
        );
    }

    private SalesItemAmounts itemAmounts(
            LiveSkladOrderPositionPayload source
    ) {
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
                    "order item sold price must not exceed list price"
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

    private CostQuality costQuality(
            LiveSkladOrderPositionPayload source,
            AnalyticsCategory category
    ) {
        if (source.costAmount() == null) {
            return CostQuality.MISSING;
        }
        if (source.costAmount().signum() != 0) {
            return CostQuality.KNOWN;
        }
        return source.work() || category.permitsZeroCost()
                ? CostQuality.ZERO_SERVICE
                : CostQuality.ZERO_UNEXPECTED;
    }

    private boolean removePayments(
            SalesDocument document,
            Accumulator result
    ) {
        boolean changed = false;
        for (var payment : factRepositories.payments()
                .findAllBySalesDocumentId(document.getId())) {
            if (payment.markDeleted()) {
                changed = true;
            }
        }
        return changed;
    }

    private void markMissingPositionsDeleted(
            SyncContext context,
            String orderExternalId,
            Instant sourceUpdatedAt,
            Set<String> seenDocumentIds
    ) {
        for (SalesDocument document : existingOrderPositions(
                context.syncRun().getConnection().getId(),
                orderExternalId
        )) {
            if (!seenDocumentIds.contains(document.getExternalId())
                    && document.acceptsSourceVersion(sourceUpdatedAt)) {
                RawRecordVersion deletionVersion = deletionRawVersion(
                        context,
                        document,
                        sourceUpdatedAt
                );
                if (document.markDeletedFromLiveSklad(
                        context.syncRun(),
                        sourceUpdatedAt,
                        deletionVersion
                )) {
                    context.result().documentsDeleted++;
                }
                if (!deletionVersion.isNormalized()) {
                    deletionVersion.markNormalized(context.now());
                }
            }
        }
    }

    private RawRecordVersion deletionRawVersion(
            SyncContext context,
            SalesDocument document,
            Instant sourceUpdatedAt
    ) {
        PreparedRawPayload preparedPayload = payloadHasher.prepare(
                RawPayloadProfile.SALE_DOCUMENT,
                retainedDeletionPayload(document, sourceUpdatedAt)
        );
        return factRepositories.rawRecords().findStoreRecordVersion(
                context.syncRun().getConnection().getId(),
                document.getStore().getId(),
                SourceSystem.LIVESKLAD.name(),
                RAW_ENTITY_TYPE,
                document.getExternalId(),
                preparedPayload.sha256()
        ).map(existing -> {
            existing.markSeenWithRetainedPayload(
                    context.syncRun(),
                    context.now(),
                    preparedPayload.json()
            );
            return existing;
        }).orElseGet(() -> factRepositories.rawRecords().save(
                RawRecordVersion.pending(
                        new RawRecordDescriptor(
                                context.syncRun().getConnection(),
                                document.getStore(),
                                SourceSystem.LIVESKLAD,
                                RAW_ENTITY_TYPE,
                                document.getExternalId(),
                                sourceUpdatedAt
                        ),
                        preparedPayload.json(),
                        preparedPayload.sha256(),
                        context.syncRun(),
                        context.now()
                )
        ));
    }

    private ObjectNode retainedDeletionPayload(
            SalesDocument document,
            Instant sourceUpdatedAt
    ) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode list = root.putObject("list");
        list.put("id", document.getExternalId());
        list.put("date", sourceUpdatedAt.toString());
        list.put("type", SOURCE_DOCUMENT_TYPE);
        ObjectNode amounts = list.putObject("summ");
        amounts.put("price", BigDecimal.ZERO);
        amounts.put("soldPrice", BigDecimal.ZERO);
        amounts.put("purchasePrice", BigDecimal.ZERO);

        ObjectNode detail = root.putObject("detail");
        detail.put("id", document.getExternalId());
        detail.put("date", sourceUpdatedAt.toString());
        detail.put("dateChange", sourceUpdatedAt.toString());
        detail.put("type", SOURCE_DOCUMENT_TYPE);
        detail.putObject("shop").put("id", document.getStore().getExternalId());
        ObjectNode cash = detail.putObject("cash");
        cash.put("money", BigDecimal.ZERO);
        cash.put("bank", BigDecimal.ZERO);
        cash.put("invoice", BigDecimal.ZERO);
        detail.putArray("positions");
        return root;
    }

    private List<SalesDocument> existingOrderPositions(
            UUID connectionId,
            String orderExternalId
    ) {
        return entityManager.createQuery(
                        """
                        SELECT document
                        FROM SalesDocument document
                        WHERE document.connection.id = :connectionId
                          AND document.sourceDocumentType = :sourceType
                          AND document.externalId LIKE :externalIdPrefix
                        """,
                        SalesDocument.class
                )
                .setParameter("connectionId", connectionId)
                .setParameter("sourceType", SOURCE_DOCUMENT_TYPE)
                .setParameter(
                        "externalIdPrefix",
                        documentPrefix(orderExternalId) + "%"
                )
                .getResultList();
    }

    private Employee resolveEmployee(
            SyncRun syncRun,
            LiveSkladOrderPositionPayload position
    ) {
        if (position.employeeExternalId() == null) {
            return null;
        }
        return referenceRepositories.employees()
                .findByConnectionIdAndExternalId(
                        syncRun.getConnection().getId(),
                        position.employeeExternalId()
                ).orElseThrow(() -> new IllegalStateException(
                        "Order position references an unknown employee; "
                                + "synchronize employees first"
                ));
    }

    private ObjectNode retainedPayload(
            LiveSkladOrderSource source,
            LiveSkladOrderPositionPayload position,
            String documentExternalId
    ) {
        LiveSkladOrderDetailPayload detail = source.detail();
        SalesItemAmounts amounts = itemAmounts(position);
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode list = root.putObject("list");
        list.put("id", documentExternalId);
        putNullable(list, "number", detail.documentNumber());
        list.put("date", position.occurredAt().toString());
        list.put("type", SOURCE_DOCUMENT_TYPE);
        ObjectNode summaryAmounts = list.putObject("summ");
        summaryAmounts.put("price", amounts.grossAmount());
        summaryAmounts.put("soldPrice", amounts.netAmount());
        putNullable(summaryAmounts, "purchasePrice", amounts.costAmount());

        ObjectNode retainedDetail = root.putObject("detail");
        retainedDetail.put("id", documentExternalId);
        putNullable(retainedDetail, "number", detail.documentNumber());
        retainedDetail.put("date", position.occurredAt().toString());
        retainedDetail.put("dateChange", detail.sourceUpdatedAt().toString());
        retainedDetail.put("type", SOURCE_DOCUMENT_TYPE);
        if (position.employeeExternalId() != null) {
            ObjectNode customer = retainedDetail.putObject("customer");
            customer.put("id", position.employeeExternalId());
            putNullable(customer, "name", position.employeeName());
        }
        retainedDetail.putObject("shop").put("id", detail.storeExternalId());
        ObjectNode cash = retainedDetail.putObject("cash");
        cash.put("money", BigDecimal.ZERO);
        cash.put("bank", BigDecimal.ZERO);
        cash.put("invoice", BigDecimal.ZERO);
        ArrayNode positions = retainedDetail.putArray("positions");
        ObjectNode retainedPosition = positions.addObject();
        retainedPosition.put("positionId", position.externalId());
        retainedPosition.put("nomenclatureId", position.productExternalId());
        putNullable(retainedPosition, "code", position.code());
        putNullable(retainedPosition, "article", position.sku());
        retainedPosition.put("name", position.name());
        retainedPosition.put("isWork", position.work());
        retainedPosition.put("count", position.quantity());
        retainedPosition.put("price", position.unitListPrice());
        retainedPosition.put("soldPrice", position.unitSoldPrice());
        putNullable(
                retainedPosition,
                "purchasePriceSumm",
                position.costAmount()
        );
        return root;
    }

    private void putNullable(
            ObjectNode node,
            String field,
            String value
    ) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private void putNullable(
            ObjectNode node,
            String field,
            BigDecimal value
    ) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private boolean isIssued(
            LiveSkladOrderSummaryPayload summary,
            LiveSkladOrderDetailPayload detail
    ) {
        return summary.visible()
                && detail.visible()
                && detail.closedAt() != null
                && ISSUED_STATUS.equals(normalizedStatus(summary.statusName()))
                && ISSUED_STATUS.equals(normalizedStatus(detail.statusName()));
    }

    private String normalizedStatus(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private void validateSource(
            Store store,
            LiveSkladOrderSource source
    ) {
        LiveSkladOrderSummaryPayload summary = source.summary();
        LiveSkladOrderDetailPayload detail = source.detail();
        if (detail == null
                || !summary.externalId().equals(detail.externalId())
                || !equalsNullable(
                        summary.documentNumber(),
                        detail.documentNumber()
                )
                || !summary.storeExternalId().equals(detail.storeExternalId())
                || !store.getExternalId().equals(detail.storeExternalId())
                || summary.visible() != detail.visible()
                || !summary.statusExternalId().equals(detail.statusExternalId())
                || detail.sourceUpdatedAt() == null
                || detail.sourceUpdatedAt().isBefore(detail.createdAt())) {
            throw new IllegalArgumentException(
                    "LiveSklad order list and detail are inconsistent"
            );
        }
        if (summary.rawPayload() == null || detail.rawPayload() == null) {
            throw new IllegalArgumentException(
                    "LiveSklad order raw payload is missing"
            );
        }
    }

    private void validateStore(SyncRun syncRun, Store store) {
        if (!store.isConnectedTo(syncRun.getConnection())) {
            throw new IllegalArgumentException(
                    "store and order sync run must belong to the same connection"
            );
        }
    }

    private boolean equalsNullable(String first, String second) {
        return first == null ? second == null : first.equals(second);
    }

    private String documentExternalId(
            String orderExternalId,
            String positionExternalId
    ) {
        return documentPrefix(orderExternalId) + positionExternalId;
    }

    private String documentPrefix(String orderExternalId) {
        return "order:" + orderExternalId + ":position:";
    }

    private String scopedId(SyncRun syncRun, String externalId) {
        return syncRun.getConnection().getId() + ":" + externalId;
    }

    private void synchronizeIssue(
            boolean present,
            Store store,
            QualityIssueSpec issue,
            SyncContext context
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
                    context.now()
            ));
            context.result().qualityIssuesOpened++;
        } else if (!present && existing.isPresent()) {
            existing.orElseThrow().resolve(null, context.now());
            context.result().qualityIssuesResolved++;
        }
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
        private int qualityIssuesOpened;
        private int qualityIssuesResolved;

        private OrderSyncBatchResult toResult() {
            return new OrderSyncBatchResult(
                    documentsCreated,
                    documentsUpdated,
                    documentsSkipped,
                    documentsDeleted,
                    productsCreated,
                    productsUpdated,
                    itemsCreated,
                    itemsUpdated,
                    itemsDeleted,
                    qualityIssuesOpened,
                    qualityIssuesResolved
            );
        }
    }
}
