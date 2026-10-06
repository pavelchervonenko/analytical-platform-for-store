package com.storeanalytics.sync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeanalytics.common.config.LiveSkladPayloadLimitsProperties;
import com.storeanalytics.employee.repository.EmployeeRepository;
import com.storeanalytics.integration.connection.model.IntegrationConnection;
import com.storeanalytics.integration.livesklad.dto.LiveSkladSaleDetailPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladSalePositionPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladSaleSummaryPayload;
import com.storeanalytics.integration.livesklad.observability.LiveSkladPayloadRejectionMetrics;
import com.storeanalytics.product.model.AnalyticsCategory;
import com.storeanalytics.product.model.AnalyticsCategoryKind;
import com.storeanalytics.product.model.AnalyticsCategoryRules;
import com.storeanalytics.product.model.CategoryAssignmentDetails;
import com.storeanalytics.product.model.CategoryAssignmentSource;
import com.storeanalytics.product.model.DeviceFamily;
import com.storeanalytics.product.model.Product;
import com.storeanalytics.product.model.ProductCategoryAssignment;
import com.storeanalytics.product.model.ProductConditionType;
import com.storeanalytics.product.model.ProductDetails;
import com.storeanalytics.product.model.ProductSourceKind;
import com.storeanalytics.product.repository.AnalyticsCategoryRepository;
import com.storeanalytics.product.repository.ProductCategoryAssignmentRepository;
import com.storeanalytics.product.repository.ProductRepository;
import com.storeanalytics.product.service.CatalogClassificationCutover;
import com.storeanalytics.product.service.CatalogSaleRoleSnapshotWriter;
import com.storeanalytics.product.service.LiveSkladProductIdentityResolver;
import com.storeanalytics.product.service.ProductAutoClassificationRuleEngine;
import com.storeanalytics.product.service.ProductClassificationResolver;
import com.storeanalytics.quality.repository.DataQualityIssueRepository;
import com.storeanalytics.sales.model.SalesDocument;
import com.storeanalytics.sales.model.SalesDocumentItem;
import com.storeanalytics.sales.model.CostQuality;
import com.storeanalytics.sales.model.SalesItemClassification;
import com.storeanalytics.sales.model.SalesPayment;
import com.storeanalytics.sales.repository.SalesDocumentItemRepository;
import com.storeanalytics.sales.repository.SalesDocumentRepository;
import com.storeanalytics.sales.repository.SalesPaymentRepository;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.repository.StoreRepository;
import com.storeanalytics.sync.model.RawRecordVersion;
import com.storeanalytics.sync.model.SourceSystem;
import com.storeanalytics.sync.model.SyncPeriod;
import com.storeanalytics.sync.model.SyncRun;
import com.storeanalytics.sync.repository.RawRecordVersionRepository;
import com.storeanalytics.sync.repository.SyncRunRepository;
import com.storeanalytics.sync.support.JsonPayloadHasher;
import com.storeanalytics.sync.support.RawPayloadPrivacyFilter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class SalesSyncSourceNameTest {

    private static final Instant SALE_AT = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant CATALOG_AT = Instant.parse("2026-10-04T10:00:00Z");
    private static final Instant FIRST_VERSION = Instant.parse("2026-10-02T10:00:00Z");
    private static final Instant NEXT_VERSION = Instant.parse("2026-10-03T10:00:00Z");
    private static final String CATALOG_NAME = "Later synthetic catalog label";
    private static final String RULE_VERSION = "source-name-test-assignment";
    private static final BigDecimal QUANTITY = new BigDecimal("2.000");
    private static final BigDecimal LIST_PRICE = new BigDecimal("15.00");
    private static final BigDecimal SOLD_PRICE = new BigDecimal("12.00");
    private static final BigDecimal WHOLE_COST = new BigDecimal("8.00");

    @Test
    void olderSalesKeepTheirOwnPositionNamesWithoutReplacingNewerCatalogObservation() {
        var fixture = new Fixture(CATALOG_AT);
        var first = fixture.source("sale-a", "Position label alpha", null);
        var second = fixture.source("sale-b", "Position label beta", null);

        var result = fixture.sync(first, second);

        assertThat(fixture.product.getName()).isEqualTo(CATALOG_NAME);
        assertThat(fixture.product.getSourceUpdatedAt()).isEqualTo(CATALOG_AT);
        assertThat(fixture.item("sale-a").classificationSnapshot().productNameSnapshot())
                .isEqualTo("Position label alpha");
        assertThat(fixture.item("sale-b").classificationSnapshot().productNameSnapshot())
                .isEqualTo("Position label beta");
        fixture.assertFinancialAndClassification(fixture.item("sale-a"));
        fixture.assertFinancialAndClassification(fixture.item("sale-b"));
        assertThat(result.itemsCreated()).isEqualTo(2);
        assertThat(result.productsUpdated()).isZero();
    }

    @Test
    void acceptedCorrectionChangesHistoricalPositionNameAndRepeatIsIdempotent() {
        var fixture = new Fixture(CATALOG_AT);
        fixture.sync(fixture.source("sale-a", "Initial position label", null));
        var item = fixture.item("sale-a");
        fixture.changeCurrentAssignment();

        var corrected = fixture.source("sale-a", "Corrected position label", null);
        var result = fixture.sync(corrected);

        assertThat(fixture.item("sale-a")).isSameAs(item);
        assertThat(item.classificationSnapshot().productNameSnapshot()).isEqualTo("Corrected position label");
        fixture.assertFinancialAndClassification(item);
        assertThat(fixture.product.getName()).isEqualTo(CATALOG_NAME);
        assertThat(fixture.product.getSourceUpdatedAt()).isEqualTo(CATALOG_AT);
        assertThat(result.itemsUpdated()).isOne();
        assertThat(result.productsUpdated()).isZero();

        var repeated = fixture.sync(corrected);
        assertThat(repeated.itemsUpdated()).isZero();
        assertThat(repeated.documentsUpdated()).isZero();
        assertThat(repeated.documentsSkipped()).isOne();
        fixture.assertFinancialAndClassification(item);
    }

    @Test
    void equalSourceTimestampCorrectionStillUpdatesNameWithoutChangingAmounts() {
        var fixture = new Fixture(SALE_AT);
        fixture.sync(fixture.source("sale-a", "Initial position label", FIRST_VERSION));
        var item = fixture.item("sale-a");

        var corrected = fixture.source("sale-a", "Corrected at equal source timestamp", FIRST_VERSION);
        var result = fixture.sync(corrected);

        assertThat(item.classificationSnapshot().productNameSnapshot())
                .isEqualTo("Corrected at equal source timestamp");
        assertThat(fixture.product.getName()).isEqualTo("Corrected at equal source timestamp");
        assertThat(fixture.product.getSourceUpdatedAt()).isEqualTo(SALE_AT);
        fixture.assertFinancialAndClassification(item);
        assertThat(result.itemsUpdated()).isOne();
        assertThat(fixture.sync(corrected).itemsUpdated()).isZero();
    }

    @Test
    void rejectedOlderDocumentVersionCannotRenameChangeAmountsOrDeletePositions() {
        var fixture = new Fixture(CATALOG_AT);
        fixture.sync(fixture.source("sale-a", "Accepted position label", NEXT_VERSION));
        var item = fixture.item("sale-a");
        var originalClassification = item.classificationSnapshot();
        clearInvocations(fixture.products);
        clearInvocations(fixture.items);
        clearInvocations(fixture.assignments);

        var stale = fixture.source("sale-a", "Rejected stale label", FIRST_VERSION);
        var detail = stale.detail();
        var missingItems = new LiveSkladSaleDetailPayload(
                detail.externalId(), detail.documentNumber(), detail.occurredAt(), detail.sourceUpdatedAt(),
                detail.sourceType(), detail.storeExternalId(), null, null, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, List.of(), detail.rawPayload()
        );
        var changedSummary = new LiveSkladSaleSummaryPayload(
                stale.summary().externalId(), stale.summary().documentNumber(), SALE_AT, "sale",
                new BigDecimal("90.00"), new BigDecimal("90.00"), new BigDecimal("70.00"),
                stale.summary().rawPayload()
        );
        var result = fixture.sync(new LiveSkladSaleSource(changedSummary, missingItems));

        assertThat(item.classificationSnapshot()).isEqualTo(originalClassification);
        fixture.assertFinancialAndClassification(item);
        assertThat(fixture.document("sale-a").getNetAmount()).isEqualByComparingTo("24.00");
        assertThat(fixture.document("sale-a").getCostAmount()).isEqualByComparingTo(WHOLE_COST);
        assertThat(fixture.document("sale-a").getSourceUpdatedAt()).isEqualTo(NEXT_VERSION);
        assertThat(fixture.product.getName()).isEqualTo(CATALOG_NAME);
        assertThat(item.isDeleted()).isFalse();
        assertThat(result.documentsSkipped()).isOne();
        assertThat(result.itemsUpdated()).isZero();
        assertThat(result.itemsDeleted()).isZero();
        verify(fixture.items, never()).findAllBySalesDocumentId(any());
        verify(fixture.products, never()).findByConnectionIdAndExternalId(any(), anyString());
        verify(fixture.assignments, never()).findEffectiveAssignments(any(), any());
    }

    private static final class Fixture {

        private final ObjectMapper mapper = new ObjectMapper();
        private final StoreRepository stores = mock(StoreRepository.class);
        private final AnalyticsCategoryRepository categories = mock(AnalyticsCategoryRepository.class);
        private final ProductRepository products = mock(ProductRepository.class);
        private final ProductCategoryAssignmentRepository assignments = mock(ProductCategoryAssignmentRepository.class);
        private final SalesDocumentRepository documents = mock(SalesDocumentRepository.class);
        private final SalesDocumentItemRepository items = mock(SalesDocumentItemRepository.class);
        private final SalesPaymentRepository payments = mock(SalesPaymentRepository.class);
        private final RawRecordVersionRepository raws = mock(RawRecordVersionRepository.class);
        private final SyncRunRepository runs = mock(SyncRunRepository.class);
        private final Map<String, SalesDocument> savedDocuments = new HashMap<>();
        private final List<SalesDocumentItem> savedItems = new ArrayList<>();
        private final List<SalesPayment> savedPayments = new ArrayList<>();
        private final Map<String, RawRecordVersion> savedRaws = new HashMap<>();
        private final IntegrationConnection connection;
        private final Store store;
        private final Product product;
        private final SyncRun run;
        private final AnalyticsCategory category;
        private final ProductCategoryAssignment assignment;
        private final SalesSyncPersistence persistence;
        private final SalesSyncPeriod period = new SalesSyncPeriod(SALE_AT.minusSeconds(60), SALE_AT.plusSeconds(60));

        private Fixture(Instant catalogAt) {
            connection = identified(new IntegrationConnection(
                    "synthetic", SourceSystem.LIVESKLAD, "Synthetic connection", null, null
            ));
            store = identified(Store.fromLiveSklad(connection, "store-source", "Synthetic store", null));
            product = identified(Product.fromLiveSklad(connection, "product-source", new ProductDetails(
                    null, "product-code", null, CATALOG_NAME, ProductSourceKind.PRODUCT, catalogAt
            )));
            run = identified(SyncRun.startSalesSync(
                    connection, new SyncPeriod(period.start(), period.end()), NEXT_VERSION
            ));
            category = identified(category("IPHONE_USED", DeviceFamily.IPHONE));
            assignment = identified(new ProductCategoryAssignment(product, category, new CategoryAssignmentDetails(
                    ProductConditionType.USED, CategoryAssignmentSource.MANUAL, RULE_VERSION,
                    SALE_AT.minusSeconds(3600), null, null, "Synthetic explicit assignment"
            )));
            configureReferences();
            configureFacts();
            EntityManager entityManager = mock(EntityManager.class);
            Query query = mock(Query.class);
            when(entityManager.createNativeQuery(anyString())).thenReturn(query);
            when(query.setParameter(eq("lockKey"), any())).thenReturn(query);
            var classification = new ProductClassificationResolver(
                    assignments, categories, new ProductAutoClassificationRuleEngine(),
                    new CatalogClassificationCutover("2026-10-31T22:00:00Z")
            );
            var hasher = new JsonPayloadHasher(
                    mapper, LiveSkladPayloadLimitsProperties.defaults(), LiveSkladPayloadRejectionMetrics.noop(),
                    new RawPayloadPrivacyFilter(mapper)
            );
            persistence = new SalesSyncPersistence(
                    new SalesReferenceRepositories(stores, mock(EmployeeRepository.class), categories,
                            classification, mock(CatalogSaleRoleSnapshotWriter.class)),
                    new SalesFactRepositories(documents, items, payments,
                            mock(DataQualityIssueRepository.class), runs, raws),
                    new LiveSkladProductIdentityResolver(products, entityManager), hasher, mapper,
                    Clock.fixed(Instant.parse("2026-10-05T10:00:00Z"), ZoneOffset.UTC),
                    ZoneId.of("Europe/Kaliningrad")
            );
        }

        private void configureReferences() {
            when(stores.findById(store.getId())).thenReturn(Optional.of(store));
            when(categories.findByCode("UNMAPPED"))
                    .thenReturn(Optional.of(identified(category("UNMAPPED", DeviceFamily.NONE))));
            when(products.findByConnectionIdAndExternalId(connection.getId(), product.getExternalId()))
                    .thenReturn(Optional.of(product));
            when(assignments.findEffectiveAssignments(product.getId(), SALE_AT)).thenReturn(List.of(assignment));
        }

        private void configureFacts() {
            when(runs.findById(run.getId())).thenReturn(Optional.of(run));
            when(documents.findByConnectionIdAndExternalId(eq(connection.getId()), anyString()))
                    .thenAnswer(call -> Optional.ofNullable(savedDocuments.get(call.getArgument(1))));
            when(documents.save(any(SalesDocument.class))).thenAnswer(call -> {
                SalesDocument document = identified(call.getArgument(0));
                savedDocuments.put(document.getExternalId(), document);
                return document;
            });
            when(items.findAllBySalesDocumentId(any())).thenAnswer(call -> savedItems.stream()
                    .filter(item -> item.getSalesDocument().getId().equals(call.getArgument(0))).toList());
            when(items.save(any(SalesDocumentItem.class))).thenAnswer(call -> {
                SalesDocumentItem item = identified(call.getArgument(0));
                savedItems.add(item);
                return item;
            });
            when(payments.findAllBySalesDocumentId(any())).thenAnswer(call -> savedPayments.stream()
                    .filter(payment -> payment.getSalesDocument().getId().equals(call.getArgument(0))).toList());
            when(payments.save(any(SalesPayment.class))).thenAnswer(call -> {
                SalesPayment payment = identified(call.getArgument(0));
                savedPayments.add(payment);
                return payment;
            });
            when(raws.findStoreRecordVersion(any(), any(), anyString(), anyString(), anyString(), anyString()))
                    .thenAnswer(call -> Optional.ofNullable(savedRaws.get(call.getArgument(5))));
            when(raws.save(any(RawRecordVersion.class))).thenAnswer(call -> {
                RawRecordVersion raw = identified(call.getArgument(0));
                savedRaws.put((String) ReflectionTestUtils.getField(raw, "payloadHash"), raw);
                return raw;
            });
        }

        private LiveSkladSaleSource source(String id, String name, Instant sourceVersion) {
            var position = new LiveSkladSalePositionPayload(
                    id + "-position", product.getExternalId(), product.getCode(), null, name,
                    false, QUANTITY, LIST_PRICE, SOLD_PRICE, WHOLE_COST
            );
            var raw = mapper.createObjectNode().put("id", id);
            if (sourceVersion == null) {
                raw.putNull("dateChange");
            } else {
                raw.put("dateChange", sourceVersion.toString());
            }
            raw.putArray("positions").addObject().put("positionId", position.externalId()).put("name", name);
            var summary = new LiveSkladSaleSummaryPayload(
                    id, "Synthetic " + id, SALE_AT, "sale", new BigDecimal("30.00"),
                    new BigDecimal("24.00"), WHOLE_COST, raw
            );
            var detail = new LiveSkladSaleDetailPayload(
                    id, summary.documentNumber(), SALE_AT, sourceVersion, "sale", store.getExternalId(),
                    null, null, new BigDecimal("24.00"), BigDecimal.ZERO, BigDecimal.ZERO, List.of(position), raw
            );
            return new LiveSkladSaleSource(summary, detail);
        }

        private SalesSyncBatchResult sync(LiveSkladSaleSource... sources) {
            return persistence.synchronize(run.getId(), period,
                    List.of(new StoreSalesBatch(store, List.of(sources))));
        }

        private SalesDocument document(String id) {
            return savedDocuments.get(id);
        }

        private SalesDocumentItem item(String id) {
            return savedItems.stream().filter(item -> item.getExternalId().equals(id + "-position"))
                    .findFirst().orElseThrow();
        }

        private void changeCurrentAssignment() {
            var replacement = identified(new ProductCategoryAssignment(
                    product, identified(category("SAMSUNG_NEW", DeviceFamily.SAMSUNG)), new CategoryAssignmentDetails(
                            ProductConditionType.NEW, CategoryAssignmentSource.MANUAL, "replacement-test-assignment",
                            SALE_AT.minusSeconds(3600), null, null, "Synthetic changed current assignment"
                    )
            ));
            when(assignments.findEffectiveAssignments(product.getId(), SALE_AT)).thenReturn(List.of(replacement));
        }

        private void assertFinancialAndClassification(SalesDocumentItem item) {
            SalesItemClassification snapshot = item.classificationSnapshot();
            assertThat(item.getProduct()).isSameAs(product);
            assertThat(snapshot.analyticsCategory()).isSameAs(category);
            assertThat(snapshot.categoryAssignment()).isSameAs(assignment);
            assertThat(snapshot.classificationVersion()).isEqualTo(RULE_VERSION);
            assertThat(snapshot.conditionType()).isEqualTo(ProductConditionType.USED);
            assertThat(item.getQuantity()).isEqualByComparingTo(QUANTITY);
            assertThat(item.getNetAmount()).isEqualByComparingTo("24.00");
            assertThat(item.getCostAmount()).isEqualByComparingTo(WHOLE_COST);
            assertThat(ReflectionTestUtils.getField(item, "unitPrice")).isEqualTo(LIST_PRICE);
            assertThat(ReflectionTestUtils.getField(item, "grossAmount")).isEqualTo(new BigDecimal("30.00"));
            assertThat(ReflectionTestUtils.getField(item, "discountAmount")).isEqualTo(new BigDecimal("6.00"));
            assertThat(ReflectionTestUtils.getField(item, "costQuality")).isEqualTo(CostQuality.KNOWN);
            assertThat(ReflectionTestUtils.getField(item, "work")).isEqualTo(false);
            assertThat(item.isDeleted()).isFalse();
        }

        private static AnalyticsCategory category(String code, DeviceFamily family) {
            return new AnalyticsCategory(code, "Synthetic " + code, null, new AnalyticsCategoryRules(
                    family == DeviceFamily.NONE ? AnalyticsCategoryKind.OTHER : AnalyticsCategoryKind.DEVICE,
                    family, family != DeviceFamily.NONE, family != DeviceFamily.NONE, false, null, false
            ));
        }

        private static <T> T identified(T model) {
            ReflectionTestUtils.setField(model, "id", UUID.randomUUID());
            return model;
        }
    }
}
