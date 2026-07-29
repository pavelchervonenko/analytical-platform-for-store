package com.storeanalytics.product.service;

import com.storeanalytics.audit.service.AuditAction;
import com.storeanalytics.audit.service.AuditEntityType;
import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.audit.service.AuditTarget;
import com.storeanalytics.common.exception.InvalidRequestException;
import com.storeanalytics.product.exception.ProductClassificationConflictException;
import com.storeanalytics.product.exception.ProductIdentityConflictException;
import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.integration.connection.model.IntegrationConnection;
import com.storeanalytics.integration.connection.repository.IntegrationConnectionRepository;
import com.storeanalytics.product.model.AnalyticsCategory;
import com.storeanalytics.product.model.CategoryAssignmentDetails;
import com.storeanalytics.product.model.CategoryAssignmentSource;
import com.storeanalytics.product.model.Product;
import com.storeanalytics.product.model.ProductCategoryAssignment;
import com.storeanalytics.product.repository.ProductCategoryAssignmentRepository;
import com.storeanalytics.sync.model.SourceSystem;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductCategoryImportService {

    private static final String UNMAPPED_CATEGORY_CODE = "UNMAPPED";

    private final IntegrationConnectionRepository connectionRepository;
    private final LiveSkladProductIdentityResolver identityResolver;
    private final ProductCategoryAssignmentRepository assignmentRepository;
    private final EntityManager entityManager;
    private final AppUserRepository userRepository;
    private final AuditLogService auditLogService;

    public ProductCategoryImportService(
            IntegrationConnectionRepository connectionRepository,
            LiveSkladProductIdentityResolver identityResolver,
            ProductCategoryAssignmentRepository assignmentRepository,
            EntityManager entityManager,
            AppUserRepository userRepository,
            AuditLogService auditLogService
    ) {
        this.connectionRepository = connectionRepository;
        this.identityResolver = identityResolver;
        this.assignmentRepository = assignmentRepository;
        this.entityManager = entityManager;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public ProductCategoryImportResult importAssignments(
            ProductCategoryImportCommand command,
            UUID actorId
    ) {
        AppUser actor = userRepository.findById(actorId)
                .orElseThrow(() -> new IllegalArgumentException("actor does not exist"));
        IntegrationConnection connection = findLiveSkladConnection(command.connectionKey());
        Map<String, AnalyticsCategory> categories = loadCategories(command.assignments());
        Map<String, String> productNames = new HashMap<>();
        command.assignments().forEach(entry -> productNames.put(
                entry.externalProductId(),
                entry.productName()
        ));
        LiveSkladProductIdentityResolver.CatalogResolution catalog;
        try {
            catalog = identityResolver.resolveCatalogReferences(
                    connection,
                    productNames
            );
        } catch (ProductIdentityConflictException exception) {
            throw new InvalidRequestException(exception.getMessage(), exception);
        }
        Map<String, Product> products = catalog.productsByIdentifier();

        Map<java.util.UUID, List<ExistingAssignment>> existingAssignments =
                loadExistingAssignments(products.values());
        List<ProductCategoryAssignment> newAssignments = new ArrayList<>();
        int unchanged = 0;

        for (ProductCategoryImportEntry entry : command.assignments()) {
            Product product = products.get(entry.externalProductId());
            AnalyticsCategory category = categories.get(entry.categoryCode());
            CategoryAssignmentDetails details = assignmentDetails(command, entry, actor);
            List<ExistingAssignment> existing = existingAssignments.getOrDefault(
                    product.getId(),
                    List.of()
            );
            if (existing.isEmpty()) {
                newAssignments.add(new ProductCategoryAssignment(product, category, details));
            } else if (existing.size() == 1
                    && existing.getFirst().matches(product, category, details)) {
                unchanged++;
            } else {
                throw new ProductClassificationConflictException(
                        "Product " + entry.externalProductId()
                                + " already has a conflicting category history"
                );
            }
        }

        if (!newAssignments.isEmpty()) {
            List<ProductCategoryAssignment> saved =
                    assignmentRepository.saveAllAndFlush(newAssignments);
            saved.forEach(assignment -> auditLogService.record(
                    actorId,
                    null,
                    AuditAction.ANALYTICS_PRODUCT_CLASSIFIED,
                    new AuditTarget(AuditEntityType.PRODUCT_CATEGORY_ASSIGNMENT, assignment.getId()),
                    command.changeReason(),
                    null,
                    classificationSummary(assignment)
            ));
        }
        return new ProductCategoryImportResult(
                command.assignments().size(),
                catalog.createdCount(),
                newAssignments.size(),
                unchanged
        );
    }

    private IntegrationConnection findLiveSkladConnection(String connectionKey) {
        return connectionRepository.findByConnectionKeyAndActiveTrue(connectionKey)
                .filter(connection -> connection.getSourceSystem() == SourceSystem.LIVESKLAD)
                .orElseThrow(() -> new IllegalStateException(
                        "Active LiveSklad connection " + connectionKey + " is not configured"
                ));
    }

    private Map<String, AnalyticsCategory> loadCategories(
            List<ProductCategoryImportEntry> assignments
    ) {
        Set<String> requestedCodes = assignments.stream()
                .map(ProductCategoryImportEntry::categoryCode)
                .collect(Collectors.toSet());
        if (requestedCodes.contains(UNMAPPED_CATEGORY_CODE)) {
            throw new InvalidRequestException(
                    "UNMAPPED must be represented by the absence of a category assignment"
            );
        }
        Map<String, AnalyticsCategory> categories = entityManager.createQuery(
                        """
                        SELECT category
                        FROM AnalyticsCategory category
                        WHERE category.code IN :codes
                          AND category.active = true
                        """,
                        AnalyticsCategory.class
                )
                .setParameter("codes", requestedCodes)
                .getResultStream()
                .collect(Collectors.toMap(AnalyticsCategory::getCode, Function.identity()));
        List<String> missingCodes = requestedCodes.stream()
                .filter(code -> !categories.containsKey(code))
                .sorted()
                .toList();
        if (!missingCodes.isEmpty()) {
            throw new InvalidRequestException(
                    "Unknown or inactive analytics categories: " + String.join(", ", missingCodes)
            );
        }
        return categories;
    }

    private Map<java.util.UUID, List<ExistingAssignment>> loadExistingAssignments(
            Collection<Product> products
    ) {
        if (products.isEmpty()) {
            return Map.of();
        }
        List<java.util.UUID> productIds = products.stream()
                .map(Product::getId)
                .toList();
        List<Tuple> rows = entityManager.createQuery(
                        """
                        SELECT assignment.product.id,
                               assignment.validFrom,
                               assignment.validTo,
                               assignment.assignmentSource,
                               assignment
                        FROM ProductCategoryAssignment assignment
                        WHERE assignment.product.id IN :productIds
                        ORDER BY assignment.product.id, assignment.validFrom
                        """,
                        Tuple.class
                )
                .setParameter("productIds", productIds)
                .getResultList();
        Map<java.util.UUID, List<ExistingAssignment>> result = new HashMap<>();
        for (Tuple row : rows) {
            java.util.UUID productId = row.get(0, java.util.UUID.class);
            ExistingAssignment assignment = new ExistingAssignment(
                    row.get(1, Instant.class),
                    row.get(2, Instant.class),
                    row.get(3, CategoryAssignmentSource.class),
                    row.get(4, ProductCategoryAssignment.class)
            );
            result.computeIfAbsent(productId, ignored -> new ArrayList<>()).add(assignment);
        }
        result.values().forEach(assignments -> assignments.sort(
                Comparator.comparing(ExistingAssignment::validFrom)
        ));
        return result;
    }

    private CategoryAssignmentDetails assignmentDetails(
            ProductCategoryImportCommand command,
            ProductCategoryImportEntry entry,
            AppUser actor
    ) {
        return new CategoryAssignmentDetails(
                entry.conditionType(),
                CategoryAssignmentSource.INITIAL_IMPORT,
                command.ruleVersion(),
                command.validFrom(),
                null,
                actor,
                command.changeReason()
        );
    }

    private Map<String, Object> classificationSummary(
            ProductCategoryAssignment assignment
    ) {
        return Map.of(
                "productId", assignment.getProduct().getId(),
                "externalProductId", assignment.getProduct().getExternalId(),
                "categoryCode", assignment.getAnalyticsCategory().getCode(),
                "conditionType", assignment.getConditionType(),
                "assignmentSource", assignment.getAssignmentSource(),
                "ruleVersion", assignment.getRuleVersion(),
                "validFrom", assignment.getValidFrom()
        );
    }

    private record ExistingAssignment(
            Instant validFrom,
            Instant validTo,
            CategoryAssignmentSource assignmentSource,
            ProductCategoryAssignment assignment
    ) {

        private boolean matches(
                Product product,
                AnalyticsCategory category,
                CategoryAssignmentDetails details
        ) {
            return assignment.matches(product, category)
                    && assignment.getConditionType() == details.conditionType()
                    && assignmentSource == details.assignmentSource()
                    && Objects.equals(assignment.getRuleVersion(), details.ruleVersion())
                    && Objects.equals(validFrom, details.validFrom())
                    && Objects.equals(validTo, details.validTo());
        }
    }
}
