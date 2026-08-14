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
        when(salesItemRepository
                .findAllActiveUnmappedByProductExternalIdIn(approvedIds))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.reconcileApprovedScope(
                approvedIds,
                1
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("approved dry-run");

        verify(resolver, never()).resolve(any(), any());
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
        AnalyticsCategory category = mock(AnalyticsCategory.class);
        DataQualityIssue issue = mock(DataQualityIssue.class);

        when(product.getExternalId()).thenReturn(externalId);
        when(product.getName()).thenReturn("Кабель USB-C");
        when(item.getProduct()).thenReturn(product);
        when(item.getSalesDocument()).thenReturn(document);
        when(document.getOccurredAt()).thenReturn(occurredAt);
        when(document.getConnection()).thenReturn(connection);
        when(connection.getId()).thenReturn(connectionId);
        when(salesItemRepository
                .findAllActiveUnmappedByProductExternalIdIn(Set.of(externalId)))
                .thenReturn(List.of(item));
        when(resolver.resolve(product, occurredAt)).thenReturn(Optional.of(
                new ProductClassificationResolution(
                        category,
                        null,
                        "livesklad-product-rules-v2:charger-cable",
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

        var result = service.reconcileApprovedScope(Set.of(externalId), 1);

        assertThat(result.reclassifiedItems()).isEqualTo(1);
        assertThat(result.unresolvedItems()).isZero();
        assertThat(result.resolvedQualityIssues()).isEqualTo(1);
        verify(issue).resolve(null, NOW);
    }
}
