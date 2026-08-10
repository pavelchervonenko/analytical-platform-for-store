package com.storeanalytics.product.service;

import com.storeanalytics.product.model.Product;
import com.storeanalytics.product.model.ProductCategoryAssignment;
import com.storeanalytics.product.repository.AnalyticsCategoryRepository;
import com.storeanalytics.product.repository.ProductCategoryAssignmentRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductClassificationResolver {

    private final ProductCategoryAssignmentRepository assignmentRepository;
    private final AnalyticsCategoryRepository categoryRepository;
    private final ProductAutoClassificationRuleEngine ruleEngine;

    public ProductClassificationResolver(
            ProductCategoryAssignmentRepository assignmentRepository,
            AnalyticsCategoryRepository categoryRepository,
            ProductAutoClassificationRuleEngine ruleEngine
    ) {
        this.assignmentRepository = assignmentRepository;
        this.categoryRepository = categoryRepository;
        this.ruleEngine = ruleEngine;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ProductClassificationResolution> resolve(
            Product product,
            Instant occurredAt
    ) {
        List<ProductCategoryAssignment> effective = assignmentRepository
                .findEffectiveAssignments(product.getId(), occurredAt);
        if (!effective.isEmpty()) {
            return Optional.of(resolution(effective.getFirst()));
        }

        return ruleEngine.classify(product).map(decision -> {
            var category = categoryRepository.findByCode(decision.categoryCode())
                    .orElseThrow(() -> new IllegalStateException(
                            "Auto-classification category is not configured: "
                                    + decision.categoryCode()
                    ));
            return new ProductClassificationResolution(
                    category,
                    null,
                    ProductAutoClassificationRuleEngine.RULE_VERSION
                            + ":" + decision.ruleId(),
                    decision.conditionType()
            );
        });
    }

    private ProductClassificationResolution resolution(
            ProductCategoryAssignment assignment
    ) {
        String version = assignment.getRuleVersion() == null
                ? "assignment:" + assignment.getId()
                : assignment.getRuleVersion();
        return new ProductClassificationResolution(
                assignment.getAnalyticsCategory(),
                assignment,
                version,
                assignment.getConditionType()
        );
    }
}
