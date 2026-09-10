package com.storeanalytics.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeanalytics.integration.connection.model.IntegrationConnection;
import com.storeanalytics.product.model.AnalyticsCategory;
import com.storeanalytics.product.model.Product;
import com.storeanalytics.product.model.ProductConditionType;
import com.storeanalytics.quality.model.DataQualityIssue;
import com.storeanalytics.quality.model.DataQualityStatus;
import com.storeanalytics.quality.repository.DataQualityIssueRepository;
import com.storeanalytics.sales.model.SalesDocument;
import com.storeanalytics.sales.model.SalesDocumentItem;
import com.storeanalytics.sales.model.SalesItemClassification;
import com.storeanalytics.sales.repository.SalesDocumentItemRepository;
import com.storeanalytics.store.model.Store;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProductClassificationReconciliationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-10T10:00:00Z");

    private SalesDocumentItemRepository salesItemRepository;
    private DataQualityIssueRepository qualityIssueRepository;
    private ProductClassificationResolver resolver;
    private ProductClassificationReconciliationService service;

    @BeforeEach
    void setUp() {
        salesItemRepository = mock(SalesDocumentItemRepository.class);
        qualityIssueRepository = mock(DataQualityIssueRepository.class);
        resolver = mock(ProductClassificationResolver.class);
        service = new ProductClassificationReconciliationService(
                salesItemRepository,
                qualityIssueRepository,
                resolver,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void rejectsScopeDriftBeforeChangingItems() {
        Set<String> approvedIds = Set.of("approved-product");
        UUID connectionId = UUID.randomUUID();
        when(salesItemRepository
                .findAllActiveUnmappedByConnectionIdAndProductExternalIdIn(
                        connectionId,
                        approvedIds
                ))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.reconcileApprovedScope(
                connectionId,
                approvedIds,
                1
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("approved dry-run");

        verify(resolver, never()).resolve(any(), any());
    }

    @Test
    void importedScopeIsBoundToItsConnectionAndAllowsNoExistingFacts() {
        UUID connectionId = UUID.randomUUID();
        Set<String> importedIds = Set.of("new-product");
        when(salesItemRepository
                .findAllActiveUnmappedByConnectionIdAndProductExternalIdIn(
                        connectionId,
                        importedIds
                ))
                .thenReturn(List.of());

        ProductClassificationReconciliationResult result =
                service.reconcileImportedScope(connectionId, importedIds);

        assertThat(result).isEqualTo(
                new ProductClassificationReconciliationResult(0, 0, 0, 0)
        );
        verify(salesItemRepository)
                .findAllActiveUnmappedByConnectionIdAndProductExternalIdIn(
                        connectionId,
                        importedIds
                );
    }

    @Test
    void reclassifiesOnlyExactApprovedScopeAndResolvesIssue() {
        String externalId = "approved-product";
        UUID connectionId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-08-09T10:00:00Z");
        Product product = mock(Product.class);
        SalesDocument document = mock(SalesDocument.class);
        SalesDocumentItem item = mock(SalesDocumentItem.class);
        IntegrationConnection connection = mock(IntegrationConnection.class);
        Store store = mock(Store.class);
        UUID storeId = UUID.randomUUID();
        AnalyticsCategory category = mock(AnalyticsCategory.class);
        DataQualityIssue issue = mock(DataQualityIssue.class);

        when(product.getExternalId()).thenReturn(externalId);
        when(product.getName()).thenReturn("Кабель USB-C");
        when(item.getProduct()).thenReturn(product);
        when(item.getSalesDocument()).thenReturn(document);
        when(document.getOccurredAt()).thenReturn(occurredAt);
        when(document.getConnection()).thenReturn(connection);
        when(document.getStore()).thenReturn(store);
        when(store.getId()).thenReturn(storeId);
        when(connection.getId()).thenReturn(connectionId);
        when(salesItemRepository
                .findAllActiveUnmappedByConnectionIdAndProductExternalIdIn(
                        connectionId,
                        Set.of(externalId)
                ))
                .thenReturn(List.of(item));
        when(resolver.resolve(product, occurredAt)).thenReturn(Optional.of(
                new ProductClassificationResolution(
                        category,
                        null,
                        "livesklad-product-rules-v4:charger-cable",
                        ProductConditionType.NOT_APPLICABLE
                )
        ));
        when(item.reclassify(any(SalesItemClassification.class)))
                .thenReturn(true);
        when(qualityIssueRepository
                .findByEntityTypeAndEntityIdAndIssueCodeAndStatus(
                        "PRODUCT",
                        connectionId + ":" + externalId,
                        "UNMAPPED_PRODUCT",
                        DataQualityStatus.OPEN
                )).thenReturn(Optional.of(issue));

        var result = service.reconcileApprovedScope(
                connectionId,
                Set.of(externalId),
                1
        );

        assertThat(result.reclassifiedItems()).isEqualTo(1);
        assertThat(result.unresolvedItems()).isZero();
        assertThat(result.resolvedQualityIssues()).isEqualTo(1);
        assertThat(result.affectedStoreIds()).containsExactly(storeId);
        verify(issue).resolve(null, NOW);
    }

    @Test
    void importedScopeDoesNotBypassAssignmentValidFromWithAutoRules() {
        UUID connectionId = UUID.randomUUID();
        Product product = mock(Product.class);
        SalesDocument document = mock(SalesDocument.class);
        SalesDocumentItem item = mock(SalesDocumentItem.class);
        IntegrationConnection connection = mock(IntegrationConnection.class);
        Instant occurredAt = Instant.parse("2026-08-31T10:00:00Z");

        when(product.getExternalId()).thenReturn("earpods");
        when(item.getProduct()).thenReturn(product);
        when(item.getSalesDocument()).thenReturn(document);
        when(document.getOccurredAt()).thenReturn(occurredAt);
        when(document.getConnection()).thenReturn(connection);
        when(connection.getId()).thenReturn(connectionId);
        when(salesItemRepository
                .findAllActiveUnmappedByConnectionIdAndProductExternalIdIn(
                        connectionId,
                        Set.of("earpods")
                ))
                .thenReturn(List.of(item));
        when(resolver.resolveAssigned(product, occurredAt)).thenReturn(Optional.empty());

        ProductClassificationReconciliationResult result =
                service.reconcileImportedScope(connectionId, Set.of("earpods"));

        assertThat(result.reclassifiedItems()).isZero();
        assertThat(result.unresolvedItems()).isOne();
        assertThat(result.affectedStoreIds()).isEmpty();
        verify(resolver, never()).resolve(product, occurredAt);
        verify(item, never()).reclassify(any());
    }

    @Test
    void linkedReturnInheritsClassificationFromOriginalSale() {
        UUID connectionId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        Product product = mock(Product.class);
        SalesDocument document = mock(SalesDocument.class);
        SalesDocumentItem originalItem = mock(SalesDocumentItem.class);
        SalesDocumentItem returnItem = mock(SalesDocumentItem.class);
        IntegrationConnection connection = mock(IntegrationConnection.class);
        Store store = mock(Store.class);
        AnalyticsCategory category = mock(AnalyticsCategory.class);
        SalesItemClassification inherited = new SalesItemClassification(
                "Apple EarPods (Lightning) A1748",
                null,
                category,
                null,
                "manual-import-v1",
                ProductConditionType.NEW
        );

        when(product.getExternalId()).thenReturn("earpods");
        when(returnItem.getProduct()).thenReturn(product);
        when(returnItem.getSalesDocument()).thenReturn(document);
        when(returnItem.getOriginalItem()).thenReturn(originalItem);
        when(originalItem.classificationSnapshot()).thenReturn(inherited);
        when(category.getCode()).thenReturn("PODS_WATCH_OTHER_DEVICE");
        when(document.getConnection()).thenReturn(connection);
        when(connection.getId()).thenReturn(connectionId);
        when(document.getStore()).thenReturn(store);
        when(store.getId()).thenReturn(storeId);
        when(salesItemRepository
                .findAllActiveUnmappedByConnectionIdAndProductExternalIdIn(
                        connectionId,
                        Set.of("earpods")
                ))
                .thenReturn(List.of(returnItem));
        when(returnItem.reclassify(inherited)).thenReturn(true);

        ProductClassificationReconciliationResult result =
                service.reconcileImportedScope(connectionId, Set.of("earpods"));

        assertThat(result.reclassifiedItems()).isOne();
        assertThat(result.unresolvedItems()).isZero();
        assertThat(result.affectedStoreIds()).containsExactly(storeId);
        verify(returnItem).reclassify(inherited);
        verify(resolver, never()).resolveAssigned(any(), any());
        verify(resolver, never()).resolve(any(), any());
    }
}
