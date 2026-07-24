package com.storeanalytics.salary.service;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.audit.service.AuditAction;
import com.storeanalytics.audit.service.AuditEntityType;
import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.audit.service.AuditTarget;
import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.product.model.Product;
import com.storeanalytics.product.repository.ProductRepository;
import com.storeanalytics.salary.model.PayrollCategoryCode;
import com.storeanalytics.salary.exception.PayrollSchemeConflictException;
import com.storeanalytics.product.exception.ProductClassificationConflictException;
import com.storeanalytics.product.exception.ProductNotFoundException;
import com.storeanalytics.salary.model.PayrollScheme;
import com.storeanalytics.salary.model.PayrollSchemeDefinition;
import com.storeanalytics.salary.model.ProductPayrollCategoryAssignment;
import com.storeanalytics.salary.repository.PayrollSchemeRepository;
import com.storeanalytics.salary.repository.ProductPayrollCategoryAssignmentRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PayrollConfigurationService {

    private final PayrollSchemeRepository schemeRepository;
    private final ProductPayrollCategoryAssignmentRepository assignmentRepository;
    private final ProductRepository productRepository;
    private final AppUserRepository userRepository;
    private final AuditLogService auditLogService;

    public PayrollConfigurationService(
            PayrollSchemeRepository schemeRepository,
            ProductPayrollCategoryAssignmentRepository assignmentRepository,
            ProductRepository productRepository,
            AppUserRepository userRepository,
            AuditLogService auditLogService
    ) {
        this.schemeRepository = schemeRepository;
        this.assignmentRepository = assignmentRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public List<PayrollSchemeView> schemes() {
        return schemeRepository.findAllByOrderByEffectiveFromDesc().stream()
                .map(this::schemeView)
                .toList();
    }

    @Transactional
    public PayrollSchemeView addScheme(
            String code,
            LocalDate effectiveFrom,
            PayrollSchemeDefinition definition,
            UUID actorId
    ) {
        if (schemeRepository.existsByCode(code)) {
            throw new PayrollSchemeConflictException("payroll scheme code already exists");
        }
        LocalDate date = requireNonNull(effectiveFrom, "effectiveFrom");
        if (schemeRepository.existsByEffectiveFrom(date)) {
            throw new PayrollSchemeConflictException("payroll scheme date already exists");
        }
        schemeRepository.findAllByOrderByEffectiveFromDesc().stream().findFirst()
                .filter(latest -> !date.isAfter(latest.getEffectiveFrom()))
                .ifPresent(latest -> {
                    throw new PayrollSchemeConflictException(
                            "new payroll scheme must start after the latest scheme"
                    );
                });
        PayrollScheme scheme = new PayrollScheme(
                code, date, definition, requireActor(actorId)
        );
        PayrollScheme saved = schemeRepository.saveAndFlush(scheme);
        auditLogService.record(
                actorId,
                null,
                AuditAction.PAYROLL_SCHEME_CREATED,
                new AuditTarget(AuditEntityType.PAYROLL_SCHEME, saved.getId()),
                null,
                null,
                schemeSummary(saved)
        );
        return schemeView(saved);
    }

    @Transactional(readOnly = true)
    public List<ProductPayrollCategoryView> productAssignments(UUID productId) {
        Product product = requireProduct(productId);
        return assignmentRepository.findAllByProductIdOrderByValidFromDesc(product.getId())
                .stream().map(this::assignmentView).toList();
    }

    @Transactional
    public ProductPayrollCategoryView assignProduct(
            UUID productId,
            PayrollCategoryCode categoryCode,
            LocalDate validFrom,
            String reason,
            UUID actorId
    ) {
        Product product = requireProduct(productId);
        LocalDate date = requireNonNull(validFrom, "validFrom");
        AppUser actor = requireActor(actorId);
        ProductPayrollCategoryAssignment current = assignmentRepository
                .findFirstByProductIdAndValidToIsNull(product.getId())
                .orElse(null);
        Map<String, Object> before = current == null ? null : assignmentSummary(current);
        java.util.Optional.ofNullable(current)
                .ifPresent(openAssignment -> {
                    if (!date.isAfter(openAssignment.getValidFrom())) {
                        throw new ProductClassificationConflictException(
                                "validFrom must be after the current assignment start"
                        );
                    }
                    openAssignment.close(date);
                    assignmentRepository.saveAndFlush(openAssignment);
                });
        ProductPayrollCategoryAssignment assignment =
                new ProductPayrollCategoryAssignment(
                        product, categoryCode, date, actor, reason
                );
        ProductPayrollCategoryAssignment saved =
                assignmentRepository.saveAndFlush(assignment);
        auditLogService.record(
                actorId,
                null,
                AuditAction.PAYROLL_PRODUCT_CLASSIFIED,
                new AuditTarget(AuditEntityType.PRODUCT_PAYROLL_CATEGORY_ASSIGNMENT, saved.getId()),
                reason,
                before,
                assignmentSummary(saved)
        );
        return assignmentView(saved);
    }

    private Product requireProduct(UUID productId) {
        UUID validated = requireNonNull(productId, "productId");
        return productRepository.findById(validated)
                .orElseThrow(() -> new ProductNotFoundException(validated));
    }

    private AppUser requireActor(UUID actorId) {
        return userRepository.findById(requireNonNull(actorId, "actorId"))
                .orElseThrow(() -> new IllegalArgumentException("actor does not exist"));
    }

    private Map<String, Object> schemeSummary(PayrollScheme scheme) {
        return Map.of(
                "code", scheme.getCode(),
                "effectiveFrom", scheme.getEffectiveFrom(),
                "achievedPercentage", scheme.getAchievedPercentage(),
                "missedPercentage", scheme.getMissedPercentage(),
                "achievedTier1Rate", scheme.getAchievedTier1Rate(),
                "missedTier1Rate", scheme.getMissedTier1Rate(),
                "achievedTier2Rate", scheme.getAchievedTier2Rate(),
                "missedTier2Rate", scheme.getMissedTier2Rate(),
                "advanceAmount", scheme.getAdvanceAmount()
        );
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

    private PayrollSchemeView schemeView(PayrollScheme scheme) {
        return new PayrollSchemeView(
                scheme.getId(), scheme.getCode(), scheme.getEffectiveFrom(),
                scheme.getAchievedPercentage(), scheme.getMissedPercentage(),
                scheme.getAchievedTier1Rate(), scheme.getMissedTier1Rate(),
                scheme.getAchievedTier2Rate(), scheme.getMissedTier2Rate(),
                scheme.getAdvanceAmount()
        );
    }

    private ProductPayrollCategoryView assignmentView(
            ProductPayrollCategoryAssignment assignment
    ) {
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
