package com.storeanalytics.performance.service;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.audit.service.AuditAction;
import com.storeanalytics.audit.service.AuditEntityType;
import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.audit.service.AuditTarget;
import com.storeanalytics.employee.model.EmployeeStoreAssignment;
import com.storeanalytics.employee.model.EmployeeStoreAssignmentId;
import com.storeanalytics.employee.repository.EmployeeStoreAssignmentRepository;
import com.storeanalytics.metrics.exception.StoreNotFoundException;
import com.storeanalytics.performance.exception.EmployeeAssignmentNotFoundException;
import com.storeanalytics.performance.exception.EmployeeRatingConflictException;
import com.storeanalytics.common.exception.InvalidRequestException;
import com.storeanalytics.store.repository.StoreRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmployeeRatingSettingsService {

    private final EmployeeStoreAssignmentRepository assignmentRepository;
    private final StoreRepository storeRepository;
    private final AuditLogService auditLogService;

    public EmployeeRatingSettingsService(
            EmployeeStoreAssignmentRepository assignmentRepository,
            StoreRepository storeRepository,
            AuditLogService auditLogService
    ) {
        this.assignmentRepository = assignmentRepository;
        this.storeRepository = storeRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public List<EmployeeRatingSettingView> findAll(UUID storeId) {
        UUID validatedStoreId = requireStore(storeId);
        return assignmentRepository.findAllByStoreId(validatedStoreId).stream()
                .map(this::toView)
                .sorted(Comparator.comparing(
                        EmployeeRatingSettingView::displayName,
                        String.CASE_INSENSITIVE_ORDER
                ))
                .toList();
    }

    @Transactional
    public EmployeeRatingSettingView updateParticipation(
            UUID storeId,
            UUID employeeId,
            boolean participatesInRanking,
            long expectedVersion,
            UUID actorId
    ) {
        UUID validatedStoreId = requireStore(storeId);
        UUID validatedEmployeeId = requireNonNull(employeeId, "employeeId");
        if (expectedVersion < 0) {
            throw new InvalidRequestException("version must not be negative");
        }
        EmployeeStoreAssignment assignment = assignmentRepository.findById(
                new EmployeeStoreAssignmentId(validatedEmployeeId, validatedStoreId)
        ).orElseThrow(() -> new EmployeeAssignmentNotFoundException(
                validatedStoreId, validatedEmployeeId
        ));
        if (assignment.getVersion() != expectedVersion) {
            throw new EmployeeRatingConflictException("Employee rating setting was changed by another user");
        }
        Map<String, Object> before = participationSummary(assignment);
        assignment.update(assignment.isActive(), participatesInRanking);
        EmployeeStoreAssignment saved = assignmentRepository.saveAndFlush(assignment);
        auditLogService.record(
                actorId,
                validatedStoreId,
                AuditAction.EMPLOYEE_RATING_PARTICIPATION_CHANGED,
                new AuditTarget(AuditEntityType.EMPLOYEE_STORE_ASSIGNMENT, validatedEmployeeId),
                null,
                before,
                participationSummary(saved)
        );
        return toView(saved);
    }

    private Map<String, Object> participationSummary(
            EmployeeStoreAssignment assignment
    ) {
        return Map.of(
                "employeeId", assignment.getEmployee().getId(),
                "participatesInRanking", assignment.participatesInRanking(),
                "version", assignment.getVersion()
        );
    }

    private UUID requireStore(UUID storeId) {
        UUID validated = requireNonNull(storeId, "storeId");
        if (!storeRepository.existsById(validated)) {
            throw new StoreNotFoundException(validated);
        }
        return validated;
    }

    private EmployeeRatingSettingView toView(EmployeeStoreAssignment assignment) {
        return new EmployeeRatingSettingView(
                assignment.getEmployee().getId(),
                assignment.getEmployee().getFullName(),
                assignment.getEmployee().isActive(),
                assignment.isActive(),
                assignment.participatesInRanking(),
                assignment.getVersion(),
                assignment.getUpdatedAt()
        );
    }
}
