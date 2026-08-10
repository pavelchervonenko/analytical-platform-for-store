package com.storeanalytics.product.service;

import com.storeanalytics.product.model.Product;
import com.storeanalytics.quality.model.DataQualityStatus;
import com.storeanalytics.quality.repository.DataQualityIssueRepository;
import com.storeanalytics.sales.model.SalesDocumentItem;
import com.storeanalytics.sales.model.SalesItemClassification;
import com.storeanalytics.sales.repository.SalesDocumentItemRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductClassificationReconciliationService {

    private static final String UNMAPPED_ISSUE_CODE = "UNMAPPED_PRODUCT";

    private final SalesDocumentItemRepository salesItemRepository;
    private final DataQualityIssueRepository qualityIssueRepository;
    private final ProductClassificationResolver classificationResolver;
    private final Clock clock;

    public ProductClassificationReconciliationService(
            SalesDocumentItemRepository salesItemRepository,
            DataQualityIssueRepository qualityIssueRepository,
            ProductClassificationResolver classificationResolver,
            Clock clock
    ) {
        this.salesItemRepository = salesItemRepository;
        this.qualityIssueRepository = qualityIssueRepository;
        this.classificationResolver = classificationResolver;
        this.clock = clock;
    }

    @Transactional
    public ProductClassificationReconciliationResult reconcileApprovedScope(
            Set<String> approvedExternalProductIds,
            int expectedItemCount
    ) {
        validateRequestedScope(approvedExternalProductIds, expectedItemCount);
        List<SalesDocumentItem> items = salesItemRepository
                .findAllActiveUnmappedByProductExternalIdIn(
                        approvedExternalProductIds
                );
        validateObservedScope(items, approvedExternalProductIds, expectedItemCount);

        Map<String, Boolean> productResolution = new HashMap<>();
        int reclassified = 0;
        int unresolved = 0;
        for (SalesDocumentItem item : items) {
            Product product = item.getProduct();
            var resolved = classificationResolver.resolve(
                    product,
                    item.getSalesDocument().getOccurredAt()
            );
            if (resolved.isEmpty()) {
                unresolved++;
                productResolution.put(issueEntityId(item), false);
                continue;
            }

            var classification = resolved.orElseThrow();
            if (item.reclassify(new SalesItemClassification(
                    product.getName(),
                    null,
                    classification.category(),
                    classification.assignment(),
                    classification.version(),
                    classification.conditionType()
            ))) {
                reclassified++;
            }
            productResolution.putIfAbsent(issueEntityId(item), true);
        }

        if (unresolved > 0) {
            throw new IllegalStateException(
                    "Approved reconciliation scope contains unresolved products"
            );
        }
        int resolvedIssues = resolveQualityIssues(productResolution);
        return new ProductClassificationReconciliationResult(
                items.size(),
                reclassified,
                unresolved,
                resolvedIssues
        );
    }

    private void validateRequestedScope(
            Set<String> approvedExternalProductIds,
            int expectedItemCount
    ) {
        if (approvedExternalProductIds == null
                || approvedExternalProductIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Approved external product IDs must not be empty"
            );
        }
        if (expectedItemCount <= 0) {
            throw new IllegalArgumentException(
                    "Expected reconciliation item count must be positive"
            );
        }
    }

    private void validateObservedScope(
            List<SalesDocumentItem> items,
            Set<String> approvedExternalProductIds,
            int expectedItemCount
    ) {
        Set<String> observedIds = new HashSet<>();
        items.forEach(item -> observedIds.add(
                item.getProduct().getExternalId()
        ));
        if (items.size() != expectedItemCount
                || !observedIds.equals(approvedExternalProductIds)) {
            throw new IllegalStateException(
                    "Observed UNMAPPED scope differs from the approved dry-run"
            );
        }
    }

    private int resolveQualityIssues(Map<String, Boolean> productResolution) {
        Instant resolvedAt = clock.instant();
        int resolved = 0;
        for (Map.Entry<String, Boolean> entry : productResolution.entrySet()) {
            if (!entry.getValue()) {
                continue;
            }
            var issue = qualityIssueRepository
                    .findByEntityTypeAndEntityIdAndIssueCodeAndStatus(
                            "PRODUCT",
                            entry.getKey(),
                            UNMAPPED_ISSUE_CODE,
                            DataQualityStatus.OPEN
                    );
            if (issue.isPresent()) {
                issue.orElseThrow().resolve(null, resolvedAt);
                resolved++;
            }
        }
        return resolved;
    }

    private String issueEntityId(SalesDocumentItem item) {
        return item.getSalesDocument().getConnection().getId()
                + ":" + item.getProduct().getExternalId();
    }
}
