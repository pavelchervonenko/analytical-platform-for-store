package com.storeanalytics.performance.service;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.audit.service.AuditAction;
import com.storeanalytics.audit.service.AuditEntityType;
import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.audit.service.AuditTarget;
import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.common.exception.InvalidRequestException;
import com.storeanalytics.employee.model.EmployeeStoreAssignment;
import com.storeanalytics.employee.repository.EmployeeStoreAssignmentRepository;
import com.storeanalytics.metrics.exception.StoreNotFoundException;
import com.storeanalytics.performance.model.EmployeeWorkShift;
import com.storeanalytics.performance.repository.EmployeeWorkShiftRepository;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.repository.StoreRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkScheduleService {

    private final EmployeeWorkShiftRepository shiftRepository;
    private final EmployeeStoreAssignmentRepository assignmentRepository;
    private final StoreRepository storeRepository;
    private final AppUserRepository userRepository;
    private final AuditLogService auditLogService;

    public WorkScheduleService(
            EmployeeWorkShiftRepository shiftRepository,
            EmployeeStoreAssignmentRepository assignmentRepository,
            StoreRepository storeRepository,
            AppUserRepository userRepository,
            AuditLogService auditLogService
    ) {
        this.shiftRepository = shiftRepository;
        this.assignmentRepository = assignmentRepository;
        this.storeRepository = storeRepository;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public List<EmployeeShiftView> find(
            UUID storeId,
            LocalDate periodStart,
            LocalDate periodEnd
    ) {
        requireStore(storeId);
        LocalDate start = requireNonNull(periodStart, "periodStart");
        LocalDate end = requireNonNull(periodEnd, "periodEnd");
        if (end.isBefore(start)) {
            throw new InvalidRequestException("periodEnd must not be before periodStart");
        }
        return shiftRepository
                .findAllByStoreIdAndWorkDateBetweenOrderByWorkDateAscEmployeeFullNameAsc(
                        storeId, start, end
                ).stream()
                .filter(EmployeeWorkShift::isActive)
                .map(this::toView)
                .toList();
    }

    @Transactional
    public List<EmployeeShiftView> replaceDay(
            UUID storeId,
            LocalDate workDate,
            List<WorkShiftInput> shifts,
            UUID actorId
    ) {
        Store store = requireStoreForUpdate(storeId);
        LocalDate date = requireNonNull(workDate, "workDate");
        Map<UUID, BigDecimal> requested = requestedShifts(shifts);
        Set<UUID> requestedIds = requested.keySet();
        AppUser actor = userRepository.findById(requireNonNull(actorId, "actorId"))
                .orElseThrow(() -> new IllegalArgumentException("actor does not exist"));

        Map<UUID, EmployeeStoreAssignment> assignments = new HashMap<>();
        assignmentRepository.findAllByStoreId(store.getId()).forEach(
                assignment -> assignments.put(assignment.getEmployee().getId(), assignment)
        );
        Set<UUID> invalidIds = new HashSet<>(requestedIds);
        invalidIds.removeAll(assignments.keySet());
        if (!invalidIds.isEmpty()) {
            throw new InvalidRequestException("employees must be assigned to the store");
        }
        for (UUID employeeId : requestedIds) {
            EmployeeStoreAssignment assignment = assignments.get(employeeId);
            if (!assignment.isActive() || !assignment.getEmployee().isActive()) {
                throw new InvalidRequestException("employees in a shift must be active");
            }
        }

        Map<UUID, EmployeeWorkShift> existing = new HashMap<>();
        shiftRepository.findAllByStoreIdAndWorkDate(store.getId(), date).forEach(
                shift -> existing.put(shift.getEmployee().getId(), shift)
        );
        Map<String, Object> before = scheduleSummary(date, existing.values());
        existing.values().forEach(shift -> {
            if (requestedIds.contains(shift.getEmployee().getId())) {
                shift.setWorkedHours(requested.get(shift.getEmployee().getId()), actor);
            } else if (shift.isActive()) {
                shift.deactivate(actor);
            }
        });
        for (UUID employeeId : requestedIds) {
            if (!existing.containsKey(employeeId)) {
                EmployeeStoreAssignment assignment = assignments.get(employeeId);
                EmployeeWorkShift shift = new EmployeeWorkShift(
                        store,
                        assignment.getEmployee(),
                        date,
                        requested.get(employeeId),
                        actor
                );
                shiftRepository.save(shift);
                existing.put(employeeId, shift);
            }
        }
        shiftRepository.flush();
        List<EmployeeShiftView> result = existing.values().stream()
                .filter(EmployeeWorkShift::isActive)
                .sorted((left, right) -> left.getEmployee().getFullName()
                        .compareToIgnoreCase(right.getEmployee().getFullName()))
                .map(this::toView)
                .toList();
        auditLogService.record(
                actorId,
                store.getId(),
                AuditAction.WORK_SCHEDULE_REPLACED,
                new AuditTarget(AuditEntityType.WORK_SCHEDULE_DAY, store.getId() + ":" + date),
                null,
                before,
                scheduleSummary(date, existing.values())
        );
        return result;
    }

    private Map<String, Object> scheduleSummary(
            LocalDate date,
            Collection<EmployeeWorkShift> shifts
    ) {
        List<Map<String, Object>> active = shifts.stream()
                .filter(EmployeeWorkShift::isActive)
                .sorted((left, right) -> left.getEmployee().getId()
                        .compareTo(right.getEmployee().getId()))
                .map(shift -> Map.<String, Object>of(
                        "employeeId", shift.getEmployee().getId(),
                        "workedHours", shift.getWorkedHours()
                ))
                .toList();
        return Map.of("workDate", date, "shifts", active);
    }

    private Map<UUID, BigDecimal> requestedShifts(List<WorkShiftInput> shifts) {
        List<WorkShiftInput> validated = requireNonNull(shifts, "shifts");
        Map<UUID, BigDecimal> result = new LinkedHashMap<>();
        for (WorkShiftInput shift : validated) {
            WorkShiftInput input = requireNonNull(shift, "shift");
            UUID employeeId = requireNonNull(input.employeeId(), "employeeId");
            BigDecimal hours = EmployeeWorkShift.validateWorkedHours(input.workedHours());
            if (result.put(employeeId, hours) != null) {
                throw new InvalidRequestException(
                        "an employee cannot occur twice in one day"
                );
            }
        }
        return result;
    }

    private Store requireStore(UUID storeId) {
        UUID validated = requireNonNull(storeId, "storeId");
        return storeRepository.findById(validated)
                .orElseThrow(() -> new StoreNotFoundException(validated));
    }

    private Store requireStoreForUpdate(UUID storeId) {
        UUID validated = requireNonNull(storeId, "storeId");
        return storeRepository.findByIdForUpdate(validated)
                .orElseThrow(() -> new StoreNotFoundException(validated));
    }

    private EmployeeShiftView toView(EmployeeWorkShift shift) {
        return new EmployeeShiftView(
                shift.getId(),
                shift.getEmployee().getId(),
                shift.getEmployee().getFullName(),
                shift.getWorkDate(),
                shift.getWorkedHours(),
                shift.isActive(),
                shift.getVersion()
        );
    }
}
