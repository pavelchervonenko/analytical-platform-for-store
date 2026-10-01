package com.storeanalytics.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeanalytics.product.model.AnalyticsCategory;
import com.storeanalytics.product.model.Product;
import com.storeanalytics.product.model.ProductConditionType;
import com.storeanalytics.product.model.ProductSourceKind;
import com.storeanalytics.product.repository.AnalyticsCategoryRepository;
import com.storeanalytics.product.repository.ProductCategoryAssignmentRepository;
import com.storeanalytics.sales.model.SalesDocument;
import com.storeanalytics.sales.model.SalesDocumentItem;
import com.storeanalytics.sales.model.SalesItemClassification;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CatalogClassificationCutoverTest {
    private static final Instant START = Instant.parse("2026-10-01T00:00:00Z");
    private final ProductCategoryAssignmentRepository assignments = mock(ProductCategoryAssignmentRepository.class);
    private final AnalyticsCategoryRepository categories = mock(AnalyticsCategoryRepository.class);
    private final ProductAutoClassificationRuleEngine rules = new ProductAutoClassificationRuleEngine();
    private final ProductClassificationResolver resolver = new ProductClassificationResolver(
            assignments, categories, rules, new CatalogClassificationCutover(START.toString()));

    @Test
    void boundaryUsesEventTimeAndRequiresOffset() {
        var boundary = new CatalogClassificationCutover("2026-10-01T03:00:00+03:00");
        assertThat(boundary.isHistorical(START.minusNanos(1))).isTrue();
        assertThat(boundary.isHistorical(START)).isFalse();
        assertThat(boundary.isHistorical(START.plusSeconds(1))).isFalse();
        assertThat(new CatalogClassificationCutover("").isHistorical(START)).isFalse();
        assertThatThrownBy(() -> new CatalogClassificationCutover("2026-10-01"))
                .isInstanceOf(java.time.format.DateTimeParseException.class);
    }

    @Test
    void businessDayBoundaryMapsTheSameInstantToThePayrollDate() {
        var midnight = new CatalogClassificationCutover("2026-09-30T22:00:00Z");
        assertThat(midnight.isBusinessDayBoundary()).isTrue();
        assertThat(midnight.activationBusinessDate()).isEqualTo(java.time.LocalDate.of(2026, 10, 1));
        assertThat(new CatalogClassificationCutover("2026-10-01T00:00:00Z")
                .isBusinessDayBoundary()).isFalse();
    }

    @Test
    void lateOldSaleUsesFrozenRulesAndRetiredHistoricalCategory() {
        Product product = product("AirPods Pro");
        AnalyticsCategory oldCategory = category("PODS_WATCH_OTHER_DEVICE", false);
        var result = resolver.resolve(product, START.minusSeconds(1)).orElseThrow();
        assertThat(result.category()).isSameAs(oldCategory);
        assertThat(result.version()).startsWith("livesklad-product-rules-v9:");
    }

    @Test
    void saleAtBoundaryUsesNewRules() {
        Product product = product("AirPods Pro");
        AnalyticsCategory newCategory = category("HEADPHONES_APPLE", true);
        var result = resolver.resolve(product, START).orElseThrow();
        assertThat(result.category()).isSameAs(newCategory);
        assertThat(result.version()).startsWith(ProductAutoClassificationRuleEngine.RULE_VERSION + ":");
    }

    @Test
    void historicalResyncPreservesWholeClassificationTupleEvenWhenNameChanged() {
        Product product = product("Apple iPhone 17 256GB");
        SalesDocumentItem item = mock(SalesDocumentItem.class);
        SalesDocument document = mock(SalesDocument.class);
        AnalyticsCategory oldCategory = mock(AnalyticsCategory.class);
        when(item.getSalesDocument()).thenReturn(document);
        when(document.isSale()).thenReturn(true);
        when(item.getProduct()).thenReturn(product);
        when(item.classificationSnapshot()).thenReturn(new SalesItemClassification(
                "Historical name", null, oldCategory, null, "confirmed-old", ProductConditionType.USED));
        var result = resolver.resolveSaleForSync(product, START.minusSeconds(1), item).orElseThrow();
        assertThat(result.category()).isSameAs(oldCategory);
        assertThat(result.conditionType()).isEqualTo(ProductConditionType.USED);
        assertThat(result.version()).isEqualTo("confirmed-old");
        verifyNoInteractions(assignments, categories);
    }

    @Test
    void replacementProductDoesNotInheritUnrelatedStoredClassification() {
        Product product = product("AirPods Pro");
        Product replaced = product("iPhone 17 256GB");
        SalesDocumentItem item = mock(SalesDocumentItem.class);
        SalesDocument document = mock(SalesDocument.class);
        when(item.getSalesDocument()).thenReturn(document);
        when(document.isSale()).thenReturn(true);
        when(item.getProduct()).thenReturn(replaced);
        AnalyticsCategory legacy = category("PODS_WATCH_OTHER_DEVICE", true);
        assertThat(resolver.resolveSaleForSync(product, START.minusSeconds(1), item)
                .orElseThrow().category()).isSameAs(legacy);
    }

    @Test
    void newlyCreatedProductWaitsForManagerOnAndAfterBoundary() {
        Product product = product("AirPods Pro");
        when(product.getCreatedAt()).thenReturn(START);

        assertThat(resolver.resolve(product, START)).isEmpty();
        assertThat(resolver.resolve(product, START.plusSeconds(1))).isEmpty();
        verifyNoInteractions(categories);
    }

    @Test
    void lateOldSaleOfNewlyCreatedProductStillUsesFrozenRules() {
        Product product = product("AirPods Pro");
        when(product.getCreatedAt()).thenReturn(START.plusSeconds(1));
        AnalyticsCategory legacy = category("PODS_WATCH_OTHER_DEVICE", false);

        assertThat(resolver.resolve(product, START.minusSeconds(1))
                .orElseThrow().category()).isSameAs(legacy);
    }

    private Product product(String name) {
        Product product = mock(Product.class);
        when(product.getId()).thenReturn(UUID.randomUUID());
        when(product.getName()).thenReturn(name);
        when(product.getSourceKind()).thenReturn(ProductSourceKind.PRODUCT);
        when(product.getCreatedAt()).thenReturn(START.minusSeconds(1));
        return product;
    }

    private AnalyticsCategory category(String code, boolean active) {
        AnalyticsCategory category = mock(AnalyticsCategory.class);
        when(category.isActive()).thenReturn(active);
        when(categories.findByCode(code)).thenReturn(Optional.of(category));
        return category;
    }
}
