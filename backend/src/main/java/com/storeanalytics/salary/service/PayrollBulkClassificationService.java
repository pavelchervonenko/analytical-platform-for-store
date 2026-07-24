package com.storeanalytics.salary.service;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import com.storeanalytics.audit.service.AuditAction;
import com.storeanalytics.audit.service.AuditEntityType;
import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.audit.service.AuditTarget;
import com.storeanalytics.common.exception.InvalidRequestException;
import com.storeanalytics.product.exception.ProductClassificationConflictException;
import com.storeanalytics.product.exception.ProductNotFoundException;
import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.product.model.Product;
import com.storeanalytics.product.repository.ProductRepository;
import com.storeanalytics.salary.model.ProductPayrollCategoryAssignment;
import com.storeanalytics.salary.repository.ProductPayrollCategoryAssignmentRepository;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PayrollBulkClassificationService {

    private final ProductRepository productRepository;
    private final ProductPayrollCategoryAssignmentRepository assignmentRepository;
    private final AppUserRepository userRepository;
    private final AuditLogService auditLogService;

    public PayrollBulkClassificationService(
            ProductRepository productRepository,
            ProductPayrollCategoryAssignmentRepository assignmentRepository,
            AppUserRepository userRepository,
            AuditLogService auditLogService
    ) {
        this.productRepository = productRepository;
        this.assignmentRepository = assignmentRepository;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public List<ProductPayrollCategoryView> assign(
            LocalDate validFrom,
            String reason,
            List<PayrollProductCategoryChange> changes,
            UUID actorId
    ) {
        LocalDate date = requireNonNull(validFrom, "validFrom");
        if (date.getDayOfMonth() != 1) {
            throw new InvalidRequestException("validFrom must be the first day of a month");
        }
        String validatedReason = requireText(reason, "reason");
        List<PayrollProductCategoryChange> validatedChanges = List.copyOf(
                requireNonNull(changes, "assignments")
        );
        if (validatedChanges.isEmpty()) {
            throw new InvalidRequestException("assignments must not be empty");
        }
        AppUser actor = userRepository.findById(requireNonNull(actorId, "actorId"))
                .orElseThrow(() -> new IllegalStateException("actor does not exist"));
        Set<UUID> productIds = new HashSet<>();
        List<Product> products = validatedChanges.stream().map(change -> {
            UUID productId = requireNonNull(change.productId(), "productId");
            requireNonNull(change.categoryCode(), "categoryCode");
            if (!productIds.add(productId)) {
                throw new InvalidRequestException("product is repeated in assignments");
            }
            return productRepository.findById(productId)
                    .orElseThrow(() -> new ProductNotFoundException(productId));
        }).toList();
        Map<UUID, Map<String, Object>> before = new HashMap<>();
        for (int index = 0; index < validatedChanges.size(); index++) {
            Product product = products.get(index);
            assignmentRepository.findFirstByProductIdAndValidToIsNull(product.getId())
                    .ifPresent(current -> before.put(
                            product.getId(), assignmentSummary(current)
                    ));
            closeCurrent(product, date);
        }
        List<ProductPayrollCategoryAssignment> created = new java.util.ArrayList<>();
        for (int index = 0; index < validatedChanges.size(); index++) {
            PayrollProductCategoryChange change = validatedChanges.get(index);
            created.add(new ProductPayrollCategoryAssignment(
                    products.get(index),
                    change.categoryCode(),
                    date,
                    actor,
                    validatedReason
            ));
        }
        List<ProductPayrollCategoryAssignment> saved =
                assignmentRepository.saveAllAndFlush(created);
        saved.forEach(assignment -> auditLogService.record(
                actorId,
                null,
                AuditAction.PAYROLL_PRODUCT_CLASSIFIED,
                new AuditTarget(AuditEntityType.PRODUCT_PAYROLL_CATEGORY_ASSIGNMENT, assignment.getId()),
                validatedReason,
                before.get(assignment.getProduct().getId()),
                assignmentSummary(assignment)
        ));
        return saved.stream().map(this::view).toList();
    }

    private void closeCurrent(Product product, LocalDate date) {
        assignmentRepository.findFirstByProductIdAndValidToIsNull(product.getId())
                .ifPresent(current -> {
                    if (!date.isAfter(current.getValidFrom())) {
                        throw new ProductClassificationConflictException(
                                "validFrom must be after current assignment for product "
                                        + product.getId()
                        );
                    }
                    current.close(date);
                    assignmentRepository.save(current);
                });
    }

    private Map<String, Object> assignmentSummary(
            ProductPayrollCategoryAssignment assignment
    ) {
        return Map.of(
                "productId", assignment.getProduct().getId(),
                "productName", assignment.getProduct().getName(),
                "categoryCode", assignment.getCategoryCode(),
                "validFrom", assignment.getValidFrom()
        );
    }

    private ProductPayrollCategoryView view(ProductPayrollCategoryAssignment assignment) {
        return new ProductPayrollCategoryView(
                assignment.getId(),
                assignment.getProduct().getId(),
                assignment.getProduct().getName(),
                assignment.getCategoryCode(),
                assignment.getValidFrom(),
                assignment.getValidTo(),
                assignment.getAssignedBy() == null
                        ? null : assignment.getAssignedBy().getId(),
                assignment.getChangeReason(),
                assignment.getVersion(),
                assignment.getCreatedAt()
        );
    }
}
