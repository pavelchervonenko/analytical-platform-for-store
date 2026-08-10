package com.storeanalytics.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeanalytics.product.model.AnalyticsCategory;
import com.storeanalytics.product.model.Product;
import com.storeanalytics.product.model.ProductCategoryAssignment;
import com.storeanalytics.product.model.ProductConditionType;
import com.storeanalytics.product.repository.AnalyticsCategoryRepository;
import com.storeanalytics.product.repository.ProductCategoryAssignmentRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProductClassificationResolverTest {

    private ProductCategoryAssignmentRepository assignmentRepository;
    private AnalyticsCategoryRepository categoryRepository;
    private ProductAutoClassificationRuleEngine ruleEngine;
    private ProductClassificationResolver resolver;

    @BeforeEach
    void setUp() {
        assignmentRepository = mock(ProductCategoryAssignmentRepository.class);
        categoryRepository = mock(AnalyticsCategoryRepository.class);
        ruleEngine = mock(ProductAutoClassificationRuleEngine.class);
        resolver = new ProductClassificationResolver(
                assignmentRepository,
                categoryRepository,
                ruleEngine
        );
    }

    @Test
    void confirmedAssignmentHasPriorityOverAutomaticRules() {
        UUID productId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-08-09T10:00:00Z");
        Product product = mock(Product.class);
        ProductCategoryAssignment assignment =
                mock(ProductCategoryAssignment.class);
        AnalyticsCategory category = mock(AnalyticsCategory.class);
        when(product.getId()).thenReturn(productId);
        when(assignmentRepository.findEffectiveAssignments(
                productId,
                occurredAt
        )).thenReturn(List.of(assignment));
        when(assignment.getAnalyticsCategory()).thenReturn(category);
        when(assignment.getConditionType()).thenReturn(ProductConditionType.USED);
        when(assignment.getRuleVersion()).thenReturn("customer-approved-v1");

        var result = resolver.resolve(product, occurredAt);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().assignment()).isSameAs(assignment);
        assertThat(result.orElseThrow().version())
                .isEqualTo("customer-approved-v1");
        verify(ruleEngine, never()).classify(product);
    }

    @Test
    void resolvesUnassignedProductWithVersionedRule() {
        UUID productId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-08-09T10:00:00Z");
        Product product = mock(Product.class);
        AnalyticsCategory category = mock(AnalyticsCategory.class);
        when(product.getId()).thenReturn(productId);
        when(assignmentRepository.findEffectiveAssignments(
                productId,
                occurredAt
        )).thenReturn(List.of());
        when(ruleEngine.classify(product)).thenReturn(Optional.of(
                new ProductAutoClassificationDecision(
                        "CHARGER_CABLE",
                        ProductConditionType.NOT_APPLICABLE,
                        "charger-cable"
                )
        ));
        when(categoryRepository.findByCode("CHARGER_CABLE"))
                .thenReturn(Optional.of(category));

        var result = resolver.resolve(product, occurredAt);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().category()).isSameAs(category);
        assertThat(result.orElseThrow().assignment()).isNull();
        assertThat(result.orElseThrow().version())
                .isEqualTo("livesklad-product-rules-v1:charger-cable");
    }
}
