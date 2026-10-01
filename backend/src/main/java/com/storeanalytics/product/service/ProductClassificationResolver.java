package com.storeanalytics.product.service;

import com.storeanalytics.product.model.Product;
import com.storeanalytics.product.model.ProductCategoryAssignment;
import com.storeanalytics.product.repository.AnalyticsCategoryRepository;
import com.storeanalytics.product.repository.ProductCategoryAssignmentRepository;
import com.storeanalytics.sales.model.SalesDocumentItem;
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
    private final CatalogClassificationCutover cutover;
    private final LegacyProductClassificationRulesV9 legacyRules = new LegacyProductClassificationRulesV9();

    public ProductClassificationResolver(
            ProductCategoryAssignmentRepository assignmentRepository,
            AnalyticsCategoryRepository categoryRepository,
            ProductAutoClassificationRuleEngine ruleEngine,
            CatalogClassificationCutover cutover
    ) {
        this.assignmentRepository = assignmentRepository;
        this.categoryRepository = categoryRepository;
        this.ruleEngine = ruleEngine;
        this.cutover = cutover;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ProductClassificationResolution> resolve(
            Product product,
            Instant occurredAt
    ) {
        Optional<ProductClassificationResolution> assigned = resolveAssigned(product, occurredAt);
        if (assigned.isPresent()) {
            return assigned;
        }

        boolean historical = cutover.isHistorical(occurredAt);
        if (!historical && cutover.requiresReviewForNewProduct(
                product.getCreatedAt(), occurredAt)) {
            return Optional.empty();
        }
        var decisionResult = historical ? legacyRules.classify(product) : ruleEngine.classify(product);
        String ruleVersion = historical ? LegacyProductClassificationRulesV9.RULE_VERSION
                : ProductAutoClassificationRuleEngine.RULE_VERSION;
        return decisionResult.flatMap(decision -> {
            var category = categoryRepository.findByCode(decision.categoryCode())
                    .orElseThrow(() -> new IllegalStateException(
                            "Auto-classification category is not configured: "
                                    + decision.categoryCode()
                    ));
            // An inactive taxonomy leaf is not permission to assign it during sync.
            // Explicit historical assignments above remain valid after retirement.
            if (!historical && !category.isActive()) {
                return Optional.empty();
            }
            return Optional.of(new ProductClassificationResolution(
                    category,
                    null,
                    ruleVersion + ":" + decision.ruleId(),
                    decision.conditionType()
            ));
        });
    }

    /**
     * Normal sale resync is not a historical reclassification command. Preserve the stored
     * category tuple for the same product; monetary/source corrections still flow through sync.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ProductClassificationResolution> resolveSaleForSync(
            Product product, Instant occurredAt, SalesDocumentItem existing
    ) {
        if (existing != null && cutover.isHistorical(occurredAt)
                && existing.getSalesDocument().isSale()
                && product.getId() != null && product.getId().equals(existing.getProduct().getId())) {
            var snapshot = existing.classificationSnapshot();
            return Optional.of(new ProductClassificationResolution(
                    snapshot.analyticsCategory(), snapshot.categoryAssignment(),
                    snapshot.classificationVersion(), snapshot.conditionType()));
        }
        return resolve(product, occurredAt);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ProductClassificationResolution> resolveAssigned(
            Product product,
            Instant occurredAt
    ) {
        List<ProductCategoryAssignment> effective = assignmentRepository
                .findEffectiveAssignments(product.getId(), occurredAt);
        if (!effective.isEmpty()) {
            return Optional.of(resolution(effective.getFirst()));
        }
        return Optional.empty();
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
