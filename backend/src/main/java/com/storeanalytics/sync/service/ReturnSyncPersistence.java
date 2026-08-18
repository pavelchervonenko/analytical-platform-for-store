package com.storeanalytics.sync.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.storeanalytics.integration.livesklad.dto.LiveSkladCashItemPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladCashRegisterPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladCashTransactionPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladReturnDetailPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladReturnPositionPayload;
import com.storeanalytics.product.model.AnalyticsCategory;
import com.storeanalytics.product.model.Product;
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
import com.storeanalytics.store.model.CashRegister;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.sync.model.RawRecordVersion;
import com.storeanalytics.sync.model.SourceSystem;
import com.storeanalytics.sync.model.SyncRun;
import com.storeanalytics.sync.support.JsonPayloadHasher;
import com.storeanalytics.sync.support.PreparedRawPayload;
import com.storeanalytics.sync.support.RawPayloadProfile;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
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
import org.springframework.util.StringUtils;

@Service
public class ReturnSyncPersistence {

    private static final String RETURN_ENTITY_TYPE = "RETURN_DOCUMENT";
    private static final String UNMAPPED_CATEGORY_CODE = "UNMAPPED";
    private static final String UNMAPPED_CLASSIFICATION_VERSION = "unmapped-v1";

    private final ReturnReferenceRepositories referenceRepositories;
    private final ReturnFactRepositories factRepositories;
    private final LiveSkladProductIdentityResolver identityResolver;
    private final JsonPayloadHasher payloadHasher;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ZoneId businessZone;

    public ReturnSyncPersistence(
            ReturnReferenceRepositories referenceRepositories,
            ReturnFactRepositories factRepositories,
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
    ReturnSyncBatchResult synchronize(
            UUID syncRunId,
            ReturnSyncPeriod period,
            List<LiveSkladCashItemPayload> cashItems,
            List<StoreReturnBatch> batches
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
        Instant now = clock.instant();
        Accumulator result = new Accumulator();
        Context context = new Context(
                syncRun,
                period,
                unmappedCategory,
                new HashMap<>(),
                result,
                now
        );

        synchronizeCashItemDictionary(syncRun, cashItems, now);
        for (StoreReturnBatch batch : List.copyOf(batches)) {
            Store store = referenceRepositories.stores()
                    .findById(batch.store().getId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "store does not exist"
                    ));
            validateStore(syncRun, store);
            synchronizeCashRegisters(context, store, batch.cashRegisters());
            Set<String> seenDocumentIds = new HashSet<>();
            for (LiveSkladReturnSource source : batch.returns()) {
                if (!seenDocumentIds.add(source.externalId())) {
                    throw new IllegalArgumentException(
                            "return batch contains duplicate document IDs"
                    );
                }
                synchronizeReturn(context, store, source);
            }
            markMissingReturnsDeleted(
                    syncRun,
                    store,
                    period,
                    seenDocumentIds,
                    result
            );
        }
        return result.toResult();
    }

    private void synchronizeCashItemDictionary(
            SyncRun syncRun,
            List<LiveSkladCashItemPayload> cashItems,
            Instant now
    ) {
        ObjectNode wrapper = objectMapper.createObjectNode();
        ArrayNode data = wrapper.putArray("data");
        cashItems.stream()
                .sorted(Comparator.comparing(
                        LiveSkladCashItemPayload::externalId))
                .forEach(item -> {
                    if (item.rawPayload() == null) {
                        throw new IllegalArgumentException(
                                "LiveSklad cash item raw payload is missing"
                        );
                    }
                    data.add(item.rawPayload().deepCopy());
                });
        PreparedRawPayload preparedDictionary = payloadHasher.prepare(RawPayloadProfile.CASH_ITEM_DICTIONARY, wrapper);
        String hash = preparedDictionary.sha256();
        RawRecordVersion rawVersion = factRepositories.rawRecords()
                .findCompanyRecordVersion(
                        syncRun.getConnection().getId(),
                        SourceSystem.LIVESKLAD.name(),
                        "CASH_ITEM_DICTIONARY",
                        "cash-items",
                        hash
                )
                .map(existing -> {
                    existing.markSeenWithRetainedPayload(
                            syncRun,
                            now,
                            preparedDictionary.json()
                    );
                    return existing;
                })
                .orElseGet(() -> factRepositories.rawRecords().save(
                        RawRecordVersion.pendingCashItems(
                                preparedDictionary.json(),
                                hash,
                                syncRun,
                                now
                        )
                ));
        markNormalized(rawVersion, now);
    }

    private void synchronizeCashRegisters(
            Context context,
            Store store,
            List<LiveSkladCashRegisterPayload> sources
    ) {
        SyncRun syncRun = context.syncRun();
        Set<String> seenIds = new HashSet<>();
        for (LiveSkladCashRegisterPayload source : sources) {
            if (!seenIds.add(source.externalId())) {
                throw new IllegalArgumentException(
                        "cash-register batch contains duplicate IDs"
                );
            }
            if (!store.getExternalId().equals(source.storeExternalId())
                    || source.rawPayload() == null) {
                throw new IllegalArgumentException(
                        "LiveSklad cash register is inconsistent"
                );
            }
            PreparedRawPayload preparedRegister =
                    payloadHasher.prepare(RawPayloadProfile.CASH_REGISTER, source.rawPayload());
            String hash = preparedRegister.sha256();
            RawRecordVersion rawVersion = factRepositories.rawRecords()
                    .findStoreRecordVersion(
                            syncRun.getConnection().getId(),
                            store.getId(),
                            SourceSystem.LIVESKLAD.name(),
                            "CASH_REGISTER",
                            source.externalId(),
                            hash
                    )
                    .map(existing -> {
                        existing.markSeenWithRetainedPayload(
                                syncRun,
                                context.now(),
                                preparedRegister.json()
                        );
                        return existing;
                    })
                    .orElseGet(() -> factRepositories.rawRecords().save(
                            RawRecordVersion.pendingCashRegister(
                                    store,
                                    source.externalId(),
                                    preparedRegister.json(),
                                    hash,
                                    syncRun,
                                    context.now()
                            )
                    ));
            Optional<CashRegister> existing =
                    referenceRepositories.cashRegisters()
                            .findByConnectionIdAndExternalId(
                                    syncRun.getConnection().getId(),
                                    source.externalId()
                            );
            if (existing.isEmpty()) {
                referenceRepositories.cashRegisters().save(
                        CashRegister.fromLiveSklad(
                                syncRun.getConnection(),
                                store,
                                source.externalId(),
                                source.name()
                        )
                );
                context.result().registersCreated++;
            } else if (existing.get().updateFromLiveSklad(store, source.name())) {
                context.result().registersUpdated++;
            }
            markNormalized(rawVersion, context.now());
        }
        for (CashRegister existing : referenceRepositories.cashRegisters()
                .findAllByConnectionIdAndStoreId(
                        syncRun.getConnection().getId(),
                        store.getId()
                )) {
            if (!seenIds.contains(existing.getExternalId())
                    && existing.markInactive()) {
                context.result().registersDeactivated++;
            }
        }
    }

    private void synchronizeReturn(
            Context context,
            Store store,
            LiveSkladReturnSource source
    ) {
        validateSource(store, context.period(), source);
        RawRecordVersion rawVersion = rawVersion(
                context.syncRun(),
                store,
                source,
                context.now()
        );
        Optional<SalesDocument> existing = factRepositories.documents()
                .findByConnectionIdAndExternalId(
                        context.syncRun().getConnection().getId(),
                        source.externalId()
                );
        existing.ifPresent(document -> requireExistingReturn(store, document));

        if (source.deleted()) {
            synchronizeDeletedReturn(
                    context,
                    store,
                    source,
                    rawVersion,
                    existing
            );
            return;
        }

        LiveSkladReturnDetailPayload detail = source.detail();
        Optional<SalesDocument> original = factRepositories.documents()
                .findByConnectionIdAndExternalId(
                        context.syncRun().getConnection().getId(),
                        detail.originalSaleExternalId()
                )
                .filter(SalesDocument::isSale)
                .filter(document -> sameStore(document.getStore(), store));
        if (original.isEmpty()) {
            synchronizeIssue(
                    true,
                    store,
                    originalDocumentIssue(context.syncRun(), source.externalId()),
                    context.now(),
                    context.result()
            );
            if (!rawVersion.isNormalized()) {
                rawVersion.markSkipped();
            }
            context.result().documentsSkipped++;
            context.result().unresolvedDocuments++;
            return;
        }
        synchronizeIssue(
                false,
                store,
                originalDocumentIssue(context.syncRun(), source.externalId()),
                context.now(),
                context.result()
        );

        ReturnAmounts returnAmounts = returnAmounts(detail);
        Instant sourceUpdatedAt = sourceUpdatedAt(source);
        SalesDocumentIdentity identity = new SalesDocumentIdentity(
                context.syncRun().getConnection(),
                SourceSystem.LIVESKLAD,
                detail.externalId(),
                store,
                original.get().getEmployee(),
                original.get(),
                context.syncRun()
        );
        SalesDocumentDetails details = new SalesDocumentDetails(
                detail.documentNumber(),
                SalesDocumentKind.RETURN,
                detail.sourceType(),
                null,
                detail.occurredAt(),
                detail.occurredAt().atZone(businessZone).toLocalDate(),
                sourceUpdatedAt
        );
        SalesDocumentAmounts amounts = new SalesDocumentAmounts(
                returnAmounts.netAmount(),
                returnAmounts.costAmount()
        );

        boolean created = existing.isEmpty();
        boolean sourceVersionAccepted = existing
                .map(document -> document.acceptsSourceVersion(sourceUpdatedAt))
                .orElse(true);
        SalesDocument document;
        boolean changed;
        if (created) {
            document = factRepositories.documents().save(new SalesDocument(
                    identity,
                    details,
                    amounts,
                    rawVersion
            ));
            changed = true;
        } else {
            document = existing.get();
            changed = document.updateFromLiveSklad(
                    identity,
                    details,
                    amounts,
                    rawVersion
            );
        }

        if (sourceVersionAccepted) {
            changed |= synchronizeItems(
                    context,
                    store,
                    document,
                    original.get(),
                    detail
            );
            changed |= synchronizePayments(
                    document,
                    detail,
                    context.result()
            );
            reconcileReturn(context, store, source, returnAmounts);
        }
        markNormalized(rawVersion, context.now());

        if (created) {
            context.result().documentsCreated++;
        } else if (changed) {
            context.result().documentsUpdated++;
        } else {
            context.result().documentsSkipped++;
        }
    }
    private void synchronizeDeletedReturn(
            Context context,
            Store store,
            LiveSkladReturnSource source,
            RawRecordVersion rawVersion,
            Optional<SalesDocument> existing
    ) {
        boolean changed = false;
        boolean newlyDeleted = false;
        if (existing.isPresent()) {
            SalesDocument document = existing.get();
            boolean previouslyDeleted = document.isDeleted();
            changed = document.markDeletedFromLiveSklad(
                    context.syncRun(),
                    sourceUpdatedAt(source),
                    rawVersion
            );
            newlyDeleted = changed
                    && !previouslyDeleted
                    && document.isDeleted();
        }
        if (changed) {
            context.result().documentsUpdated++;
            if (newlyDeleted) {
                context.result().documentsDeleted++;
            }
        } else {
            context.result().documentsSkipped++;
        }
        synchronizeIssue(
                false,
                store,
                originalDocumentIssue(context.syncRun(), source.externalId()),
                context.now(),
                context.result()
        );
        markNormalized(rawVersion, context.now());
    }


    private RawRecordVersion rawVersion(
            SyncRun syncRun,
            Store store,
            LiveSkladReturnSource source,
            Instant now
    ) {
        ObjectNode combined = objectMapper.createObjectNode();
        ArrayNode transactions = combined.putArray("cashTransactions");
        source.cashTransactions().stream()
                .sorted(Comparator.comparing(
                        LiveSkladCashTransactionPayload::externalId))
                .forEach(transaction -> {
                    if (transaction.rawPayload() == null) {
                        throw new IllegalArgumentException(
                                "LiveSklad cash transaction raw payload is missing"
                        );
                    }
                    transactions.add(transaction.rawPayload().deepCopy());
                });
        if (source.detail() == null) {
            combined.putNull("detail");
        } else {
            combined.set("detail", source.detail().rawPayload().deepCopy());
        }
        PreparedRawPayload preparedReturn = payloadHasher.prepare(RawPayloadProfile.RETURN_DOCUMENT, combined);
        String hash = preparedReturn.sha256();
        return factRepositories.rawRecords().findStoreRecordVersion(
                syncRun.getConnection().getId(),
                store.getId(),
                SourceSystem.LIVESKLAD.name(),
                RETURN_ENTITY_TYPE,
                source.externalId(),
                hash
        ).map(existing -> {
            existing.markSeenWithRetainedPayload(
                    syncRun,
                    now,
                    preparedReturn.json()
            );
            return existing;
        }).orElseGet(() -> factRepositories.rawRecords().save(
                RawRecordVersion.pendingReturn(
                        store,
                        source.externalId(),
                        preparedReturn.json(),
                        hash,
                        sourceUpdatedAt(source),
                        syncRun,
                        now
                )
        ));
    }

    private boolean synchronizeItems(
            Context context,
            Store store,
            SalesDocument returnDocument,
            SalesDocument originalSale,
            LiveSkladReturnDetailPayload detail
    ) {
        Map<String, SalesDocumentItem> existingItems = new LinkedHashMap<>();
        for (SalesDocumentItem item : factRepositories.items()
                .findAllBySalesDocumentId(returnDocument.getId())) {
            existingItems.put(item.getExternalId(), item);
        }
        boolean changed = false;
        Set<String> seenItemIds = new HashSet<>();
        for (LiveSkladReturnPositionPayload source : detail.positions()) {
            if (!seenItemIds.add(source.externalId())) {
                throw new IllegalArgumentException(
                        "return contains duplicate position IDs"
                );
            }
            Optional<SalesDocumentItem> originalItem = factRepositories.items()
                    .findBySalesDocumentIdAndExternalId(
                            originalSale.getId(),
                            source.originalSalePositionExternalId()
                    )
                    .filter(item -> !item.isDeleted());
            Product product;
            SalesItemClassification classification;
            if (originalItem.isPresent()) {
                product = originalItem.get().getProduct();
                if (!product.getExternalId().equals(
                        source.productExternalId())) {
                    throw new IllegalArgumentException(
                            "return product differs from original sale item"
                    );
                }
                classification = originalItem.get().classificationSnapshot();
            } else {
                product = resolveProduct(context, source, detail.occurredAt());
                classification = classify(
                        product,
                        detail.occurredAt(),
                        context.unmappedCategory()
                );
            }
            synchronizeIssue(
                    originalItem.isEmpty(),
                    store,
                    qualityIssue(
                            "RETURN_ITEM",
                            scopedId(context.syncRun(), source.externalId()),
                            "RETURN_ORIGINAL_ITEM_MISSING",
                            DataQualitySeverity.WARNING,
                            "Return item cannot be linked to its original sale item"
                    ),
                    context.now(),
                    context.result()
            );
            if (originalItem.isEmpty()) {
                synchronizeIssue(
                        classification.analyticsCategory() == context.unmappedCategory(),
                        store,
                        qualityIssue(
                                "PRODUCT",
                                scopedId(
                                        context.syncRun(),
                                        source.productExternalId()
                                ),
                                "UNMAPPED_PRODUCT",
                                DataQualitySeverity.WARNING,
                                "Product has no effective analytics classification"
                        ),
                        context.now(),
                        context.result()
                );
            }

            SalesItemAmounts amounts = itemAmounts(source);
            CostQuality costQuality = costQuality(
                    source,
                    classification.analyticsCategory()
            );
            synchronizeCostIssues(
                    context,
                    store,
                    source.externalId(),
                    costQuality
            );
            SalesDocumentItem existing = existingItems.get(source.externalId());
            if (existing == null) {
                factRepositories.items().save(new SalesDocumentItem(
                        new SalesItemIdentity(
                                returnDocument,
                                source.externalId(),
                                originalItem.orElse(null),
                                product
                        ),
                        classification,
                        amounts,
                        costQuality,
                        source.work()
                ));
                context.result().itemsCreated++;
                changed = true;
            } else if (existing.update(
                    originalItem.orElse(null),
                    product,
                    classification,
                    amounts,
                    costQuality,
                    source.work()
            )) {
                context.result().itemsUpdated++;
                changed = true;
            }
        }
        for (SalesDocumentItem existing : existingItems.values()) {
            if (!seenItemIds.contains(existing.getExternalId())
                    && existing.markDeleted()) {
                context.result().itemsDeleted++;
                changed = true;
            }
        }
        return changed;
    }

    private Product resolveProduct(
            Context context,
            LiveSkladReturnPositionPayload source,
            Instant observedAt
    ) {
        Product cached = context.productCache().get(source.productExternalId());
        ProductDetails details = new ProductDetails(
                null,
                source.code(),
                source.sku(),
                source.name(),
                source.work()
                        ? ProductSourceKind.SERVICE
                        : ProductSourceKind.PRODUCT,
                observedAt
        );
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

    private SalesItemClassification classify(
            Product product,
            Instant occurredAt,
            AnalyticsCategory unmappedCategory
    ) {
        var resolved = referenceRepositories.classificationResolver()
                .resolve(product, occurredAt);
        if (resolved.isEmpty()) {
            return new SalesItemClassification(
                    product.getName(),
                    null,
                    unmappedCategory,
                    null,
                    UNMAPPED_CLASSIFICATION_VERSION,
                    ProductConditionType.UNKNOWN
            );
        }
        var classification = resolved.orElseThrow();
        return new SalesItemClassification(
                product.getName(),
                null,
                classification.category(),
                classification.assignment(),
                classification.version(),
                classification.conditionType()
        );
    }

    private SalesItemAmounts itemAmounts(
            LiveSkladReturnPositionPayload source
    ) {
        BigDecimal gross = multiplyMoney(
                source.unitListPrice(),
                source.quantity(),
                "return grossAmount"
        );
        BigDecimal net = multiplyMoney(
                source.unitSoldPrice(),
                source.quantity(),
                "return netAmount"
        );
        BigDecimal discount = gross.subtract(net);
        if (discount.signum() < 0) {
            throw new IllegalArgumentException(
                    "return item sold price must not exceed list price"
            );
        }
        return new SalesItemAmounts(
                source.quantity(),
                source.unitListPrice(),
                gross,
                discount,
                net,
                source.costAmount()
        );
    }

    private ReturnAmounts returnAmounts(
            LiveSkladReturnDetailPayload detail
    ) {
        BigDecimal net = BigDecimal.ZERO.setScale(2);
        BigDecimal cost = BigDecimal.ZERO.setScale(2);
        boolean completeCost = true;
        for (LiveSkladReturnPositionPayload position : detail.positions()) {
            SalesItemAmounts amounts = itemAmounts(position);
            net = net.add(amounts.netAmount());
            if (amounts.costAmount() == null) {
                completeCost = false;
            } else {
                cost = cost.add(amounts.costAmount());
            }
        }
        return new ReturnAmounts(net, completeCost ? cost : null);
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
            LiveSkladReturnPositionPayload source,
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

    private void synchronizeCostIssues(
            Context context,
            Store store,
            String externalId,
            CostQuality costQuality
    ) {
        synchronizeIssue(
                costQuality == CostQuality.ZERO_UNEXPECTED,
                store,
                qualityIssue(
                        "RETURN_ITEM",
                        scopedId(context.syncRun(), externalId),
                        "RETURN_ZERO_UNEXPECTED_COST",
                        DataQualitySeverity.WARNING,
                        "Non-service return item has zero cost"
                ),
                context.now(),
                context.result()
        );
        synchronizeIssue(
                costQuality == CostQuality.MISSING,
                store,
                qualityIssue(
                        "RETURN_ITEM",
                        scopedId(context.syncRun(), externalId),
                        "RETURN_MISSING_COST",
                        DataQualitySeverity.WARNING,
                        "Return item cost is missing"
                ),
                context.now(),
                context.result()
        );
    }

    private boolean synchronizePayments(
            SalesDocument document,
            LiveSkladReturnDetailPayload detail,
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

    private void reconcileReturn(
            Context context,
            Store store,
            LiveSkladReturnSource source,
            ReturnAmounts amounts
    ) {
        LiveSkladReturnDetailPayload detail = source.detail();
        BigDecimal paymentTotal = detail.cashAmount()
                .add(detail.cardAmount())
                .add(detail.bankTransferAmount());
        BigDecimal transactionTotal = source.cashTransactions().stream()
                .filter(transaction -> !transaction.deleted())
                .map(LiveSkladCashTransactionPayload::amount)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        String entityId = scopedId(context.syncRun(), source.externalId());
        synchronizeIssue(
                paymentTotal.compareTo(amounts.netAmount()) != 0,
                store,
                qualityIssue(
                        RETURN_ENTITY_TYPE,
                        entityId,
                        "RETURN_PAYMENT_MISMATCH",
                        DataQualitySeverity.WARNING,
                        "Return payment total does not match returned items"
                ),
                context.now(),
                context.result()
        );
        synchronizeIssue(
                transactionTotal.compareTo(paymentTotal) != 0,
                store,
                qualityIssue(
                        RETURN_ENTITY_TYPE,
                        entityId,
                        "RETURN_CASH_TRANSACTION_MISMATCH",
                        DataQualitySeverity.WARNING,
                        "Return cash operations do not match document payments"
                ),
                context.now(),
                context.result()
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

    private void markMissingReturnsDeleted(
            SyncRun syncRun,
            Store store,
            ReturnSyncPeriod period,
            Set<String> seenDocumentIds,
            Accumulator result
    ) {
        for (SalesDocument document : factRepositories.documents()
                .findAllByConnectionIdAndStoreIdAndDocumentKindAndOccurredAtBetween(
                        syncRun.getConnection().getId(),
                        store.getId(),
                        SalesDocumentKind.RETURN,
                        "saleReturn",
                        period.start(),
                        period.end()
                )) {
            if (!seenDocumentIds.contains(document.getExternalId())
                    && document.markDeleted(syncRun)) {
                result.documentsUpdated++;
                result.documentsDeleted++;
            }
        }
    }

    private void validateSource(
            Store store,
            ReturnSyncPeriod period,
            LiveSkladReturnSource source
    ) {
        for (LiveSkladCashTransactionPayload transaction
                : source.cashTransactions()) {
            if (!store.getExternalId().equals(transaction.storeExternalId())
                    || !source.externalId().equals(
                    transaction.documentExternalId())
                    || transaction.rawPayload() == null
                    || transaction.occurredAt().isBefore(period.start())
                    || transaction.occurredAt().isAfter(period.end())) {
                throw new IllegalArgumentException(
                        "LiveSklad return cash source is inconsistent"
                );
            }
        }
        if (source.deleted()) {
            return;
        }
        LiveSkladReturnDetailPayload detail = source.detail();
        if (!source.externalId().equals(detail.externalId())
                || !"saleReturn".equalsIgnoreCase(detail.sourceType())
                || !store.getExternalId().equals(detail.storeExternalId())
                || !StringUtils.hasText(detail.originalSaleExternalId())
                || detail.rawPayload() == null
                || detail.positions() == null
                || detail.occurredAt().isBefore(period.start())
                || detail.occurredAt().isAfter(period.end())) {
            throw new IllegalArgumentException(
                    "LiveSklad return detail is inconsistent"
            );
        }
    }

    private Instant sourceUpdatedAt(LiveSkladReturnSource source) {
        Instant result = null;
        for (LiveSkladCashTransactionPayload transaction
                : source.cashTransactions()) {
            Instant candidate = transaction.sourceUpdatedAt() == null
                    ? transaction.occurredAt()
                    : transaction.sourceUpdatedAt();
            if (result == null || candidate.isAfter(result)) {
                result = candidate;
            }
        }
        if (source.detail() != null) {
            Instant candidate = source.detail().sourceUpdatedAt() == null
                    ? source.detail().occurredAt()
                    : source.detail().sourceUpdatedAt();
            if (result == null || candidate.isAfter(result)) {
                result = candidate;
            }
        }
        return result;
    }

    private void requireExistingReturn(Store store, SalesDocument document) {
        if (!document.isReturn() || !sameStore(document.getStore(), store)) {
            throw new IllegalStateException(
                    "Return external ID conflicts with another sales document"
            );
        }
    }

    private void markNormalized(RawRecordVersion rawVersion, Instant now) {
        if (!rawVersion.isNormalized()) {
            rawVersion.markNormalized(now);
        }
    }

    private void validateStore(SyncRun syncRun, Store store) {
        if (!store.isConnectedTo(syncRun.getConnection())) {
            throw new IllegalArgumentException(
                    "store and return sync run must belong to the same connection"
            );
        }
    }

    private boolean sameStore(Store first, Store second) {
        return first == second
                || first != null
                && second != null
                && first.getId() != null
                && first.getId().equals(second.getId());
    }

    private String scopedId(SyncRun syncRun, String externalId) {
        return syncRun.getConnection().getId() + ":" + externalId;
    }

    private QualityIssueSpec originalDocumentIssue(
            SyncRun syncRun,
            String externalId
    ) {
        return qualityIssue(
                RETURN_ENTITY_TYPE,
                scopedId(syncRun, externalId),
                "RETURN_ORIGINAL_DOCUMENT_MISSING",
                DataQualitySeverity.ERROR,
                "Return cannot be linked to its original sale document"
        );
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

    private record Context(
            SyncRun syncRun,
            ReturnSyncPeriod period,
            AnalyticsCategory unmappedCategory,
            Map<String, Product> productCache,
            Accumulator result,
            Instant now
    ) {
    }

    private record ReturnAmounts(
            BigDecimal netAmount,
            BigDecimal costAmount
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

    private record PaymentComponent(
            String externalId,
            PaymentMethod method,
            BigDecimal amount
    ) {
    }

    private static final class Accumulator {

        private int registersCreated;
        private int registersUpdated;
        private int registersDeactivated;
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
        private int unresolvedDocuments;

        private ReturnSyncBatchResult toResult() {
            return new ReturnSyncBatchResult(
                    registersCreated,
                    registersUpdated,
                    registersDeactivated,
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
                    qualityIssuesResolved,
                    unresolvedDocuments
            );
        }
    }
}
