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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
            UUID connectionId,
            Set<String> approvedExternalProductIds,
            int expectedItemCount
    ) {
        if (connectionId == null) {
            throw new IllegalArgumentException("Approved connection ID must not be null");
        }
        validateRequestedScope(approvedExternalProductIds, expectedItemCount);
        List<SalesDocumentItem> items = salesItemRepository
                .findAllActiveUnmappedByConnectionIdAndProductExternalIdIn(
                        connectionId,
                        approvedExternalProductIds
                );
        validateObservedScope(items, approvedExternalProductIds, expectedItemCount);
        ProductClassificationReconciliationResult result = reconcile(items, false);
        if (result.unresolvedItems() > 0) {
            throw new IllegalStateException(
                    "Approved reconciliation scope contains unresolved products"
            );
        }
        return result;
    }

    @Transactional
    public ProductClassificationReconciliationResult reconcileImportedScope(
            UUID connectionId,
            Set<String> importedExternalProductIds
    ) {
        if (connectionId == null || importedExternalProductIds == null
                || importedExternalProductIds.isEmpty()) {
            throw new IllegalArgumentException("Imported product scope must not be empty");
        }
        List<SalesDocumentItem> items = salesItemRepository
                .findAllActiveUnmappedByConnectionIdAndProductExternalIdIn(
                        connectionId,
                        importedExternalProductIds
                );
        return reconcile(items, true);
    }

    private ProductClassificationReconciliationResult reconcile(
            List<SalesDocumentItem> items,
            boolean assignmentsOnly
    ) {
        Map<String, Boolean> productResolution = new HashMap<>();
        Set<UUID> affectedStoreIds = new HashSet<>();
        int reclassified = 0;
        int unresolved = 0;
        List<SalesDocumentItem> orderedItems = items.stream()
                .sorted(java.util.Comparator.comparing(
                        item -> item.getOriginalItem() != null
                ))
                .toList();
        for (SalesDocumentItem item : orderedItems) {
            Product product = item.getProduct();
            Optional<SalesItemClassification> classification = classification(
                    item,
                    assignmentsOnly
            );
            if (classification.isEmpty()) {
                unresolved++;
                productResolution.put(issueEntityId(item), false);
                continue;
            }

            if (item.reclassify(classification.orElseThrow())) {
                reclassified++;
                affectedStoreIds.add(item.getSalesDocument().getStore().getId());
            }
            productResolution.putIfAbsent(issueEntityId(item), true);
        }
        int resolvedIssues = resolveQualityIssues(productResolution);
        return new ProductClassificationReconciliationResult(
                items.size(),
                reclassified,
                unresolved,
                resolvedIssues,
                affectedStoreIds
        );
    }

    private Optional<SalesItemClassification> classification(
            SalesDocumentItem item,
            boolean assignmentsOnly
    ) {
        SalesDocumentItem originalItem = item.getOriginalItem();
        if (originalItem != null) {
            SalesItemClassification inherited = originalItem.classificationSnapshot();
            return "UNMAPPED".equals(inherited.analyticsCategory().getCode())
                    ? Optional.empty()
                    : Optional.of(inherited);
        }
        Product product = item.getProduct();
        var resolved = assignmentsOnly
                ? classificationResolver.resolveAssigned(
                        product, item.getSalesDocument().getOccurredAt())
                : classificationResolver.resolve(
                        product, item.getSalesDocument().getOccurredAt());
        return resolved.map(value -> new SalesItemClassification(
                product.getName(),
                null,
                value.category(),
                value.assignment(),
                value.version(),
                value.conditionType()
        ));
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
