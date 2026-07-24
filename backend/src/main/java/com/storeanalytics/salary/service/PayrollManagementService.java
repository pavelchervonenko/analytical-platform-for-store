package com.storeanalytics.salary.service;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.audit.service.AuditAction;
import com.storeanalytics.audit.service.AuditEntityType;
import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.audit.service.AuditTarget;
import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.employee.model.EmployeeStoreAssignment;
import com.storeanalytics.employee.model.EmployeeStoreAssignmentId;
import com.storeanalytics.employee.repository.EmployeeStoreAssignmentRepository;
import com.storeanalytics.common.exception.InvalidRequestException;
import com.storeanalytics.salary.exception.PayrollAdjustmentNotFoundException;
import com.storeanalytics.report.service.MonthlyReportFinalizationService;
import com.storeanalytics.salary.exception.PayrollMonthNotCalculatedException;
import com.storeanalytics.salary.exception.PayrollRunNotFoundException;
import com.storeanalytics.salary.exception.PayrollStateConflictException;
import com.storeanalytics.salary.model.PayrollAdjustment;
import com.storeanalytics.salary.model.PayrollAdjustmentType;
import com.storeanalytics.salary.model.PayrollDailyAllocation;
import com.storeanalytics.salary.model.PayrollDailyPool;
import com.storeanalytics.salary.model.PayrollEvent;
import com.storeanalytics.salary.model.PayrollEventType;
import com.storeanalytics.salary.model.PayrollRun;
import com.storeanalytics.salary.model.PayrollRunStatus;
import com.storeanalytics.salary.model.PayrollScheme;
import com.storeanalytics.salary.model.PayrollStatement;
import com.storeanalytics.salary.repository.PayrollAdjustmentRepository;
import com.storeanalytics.salary.repository.PayrollDailyAllocationRepository;
import com.storeanalytics.salary.repository.PayrollDailyPoolRepository;
import com.storeanalytics.salary.repository.PayrollEventRepository;
import com.storeanalytics.salary.repository.PayrollRunRepository;
import com.storeanalytics.salary.repository.PayrollStatementRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PayrollManagementService {

    private final PayrollCalculationService calculationService;
    private final PayrollFreshnessService freshnessService;
    private final MonthlyReportFinalizationService reportFinalizationService;
    private final PayrollRunRepository runRepository;
    private final PayrollDailyPoolRepository poolRepository;
    private final PayrollDailyAllocationRepository allocationRepository;
    private final PayrollAdjustmentRepository adjustmentRepository;
    private final PayrollStatementRepository statementRepository;
    private final PayrollEventRepository eventRepository;
    private final EmployeeStoreAssignmentRepository assignmentRepository;
    private final AppUserRepository userRepository;
    private final PayrollSnapshotStore snapshotStore;
    private final Clock clock;
    private final AuditLogService auditLogService;

    public PayrollManagementService(
            PayrollCalculationService calculationService,
            PayrollFreshnessService freshnessService,
            PayrollManagementRepositories repositories,
            EmployeeStoreAssignmentRepository assignmentRepository,
            AppUserRepository userRepository,
            PayrollSnapshotStore snapshotStore,
            PayrollManagementSupport support
    ) {
        this.calculationService = calculationService;
        this.freshnessService = freshnessService;
        this.runRepository = repositories.runs();
        this.poolRepository = repositories.pools();
        this.allocationRepository = repositories.allocations();
        this.adjustmentRepository = repositories.adjustments();
        this.statementRepository = repositories.statements();
        this.eventRepository = repositories.events();
        this.assignmentRepository = assignmentRepository;
        this.reportFinalizationService = support.reportFinalization();
        this.userRepository = userRepository;
        this.snapshotStore = snapshotStore;
        this.clock = support.clock();
        this.auditLogService = support.auditLog();
    }

    @Transactional
    public PayrollRunDetailView calculate(
            UUID storeId,
            YearMonth month,
            String revisionReason,
            UUID actorId
    ) {
        PayrollRun run = calculationService.calculate(
                storeId, requireNonNull(month, "month"), revisionReason, actorId
        );
        return detail(run);
    }

    @Transactional(readOnly = true)
    public PayrollRunDetailView latest(UUID storeId, YearMonth month) {
        PayrollRun run = runRepository
                .findFirstByStoreIdAndPeriodMonthOrderByRevisionDesc(
                        requireNonNull(storeId, "storeId"),
                        requireNonNull(month, "month").atDay(1)
                )
                .orElseThrow(() -> new PayrollMonthNotCalculatedException(storeId, month));
        return detail(run);
    }

    @Transactional(readOnly = true)
    public PayrollRunDetailView get(UUID storeId, UUID runId) {
        return detail(requireRun(storeId, runId));
    }

    @Transactional(readOnly = true)
    public List<PayrollRunSummaryView> list(UUID storeId) {
        UUID validatedStoreId = requireNonNull(storeId, "storeId");
        return runRepository.findAllByStoreIdOrderByPeriodMonthDescRevisionDesc(validatedStoreId)
                .stream().map(this::summary).toList();
    }

    @Transactional
    public PayrollRunDetailView addAdjustment(AddPayrollAdjustmentCommand command) {
        AddPayrollAdjustmentCommand validated = requireNonNull(command, "command");
        UUID storeId = validated.storeId();
        UUID runId = validated.runId();
        UUID employeeId = validated.employeeId();
        PayrollAdjustmentType type = validated.type();
        BigDecimal amount = validated.amount();
        String reason = validated.reason();
        long version = validated.version();
        UUID actorId = validated.actorId();
        PayrollRun run = requireEditableRun(storeId, runId, version);
        EmployeeStoreAssignment assignment = assignmentRepository.findById(
                new EmployeeStoreAssignmentId(
                        requireNonNull(employeeId, "employeeId"), run.getStore().getId()
                )
        ).orElseThrow(() -> new InvalidRequestException(
                "employee must be assigned to the payroll store"
        ));
        AppUser actor = requireActor(actorId);
        PayrollAdjustment adjustment = new PayrollAdjustment(
                run, assignment.getEmployee(), type, amount, reason, actor
        );
        adjustmentRepository.saveAndFlush(adjustment);
        auditLogService.record(
                actorId,
                run.getStore().getId(),
                AuditAction.PAYROLL_ADJUSTMENT_CREATED,
                new AuditTarget(AuditEntityType.PAYROLL_ADJUSTMENT, adjustment.getId()),
                reason,
                null,
                adjustmentSummary(adjustment)
        );
        snapshotStore.appendEvent(new PayrollEvent(
                run,
                PayrollEventType.ADJUSTMENT_ADDED,
                actor,
                adjustment.getId() + ": " + reason
        ));
        PayrollRun recalculated = calculationService.calculate(
                run.getStore().getId(), YearMonth.from(run.getPeriodMonth()), null, actorId
        );
        return detail(recalculated);
    }

    @Transactional
    public PayrollRunDetailView voidAdjustment(
            UUID storeId,
            UUID runId,
            UUID adjustmentId,
            String reason,
            long runVersion,
            long adjustmentVersion,
            UUID actorId
    ) {
        PayrollRun run = requireEditableRun(storeId, runId, runVersion);
        PayrollAdjustment adjustment = adjustmentRepository.findById(
                requireNonNull(adjustmentId, "adjustmentId")
        ).orElseThrow(() -> new PayrollAdjustmentNotFoundException(adjustmentId));
        if (!adjustment.getPayrollRun().getId().equals(run.getId())) {
            throw new PayrollAdjustmentNotFoundException(adjustmentId);
        }
        requireVersion(adjustment.getVersion(), adjustmentVersion, "payroll adjustment");
        AppUser actor = requireActor(actorId);
        Map<String, Object> before = adjustmentSummary(adjustment);
        if (!adjustment.isActive()) {
            throw new PayrollStateConflictException(
                    "payroll adjustment is already voided"
            );
        }
        adjustment.voidAdjustment(actor, clock.instant(), reason);
        adjustmentRepository.saveAndFlush(adjustment);
        auditLogService.record(
                actorId,
                run.getStore().getId(),
                AuditAction.PAYROLL_ADJUSTMENT_VOIDED,
                new AuditTarget(AuditEntityType.PAYROLL_ADJUSTMENT, adjustment.getId()),
                reason,
                before,
                adjustmentSummary(adjustment)
        );
        snapshotStore.appendEvent(new PayrollEvent(
                run,
                PayrollEventType.ADJUSTMENT_VOIDED,
                actor,
                adjustment.getId() + ": " + reason
        ));
        PayrollRun recalculated = calculationService.calculate(
                run.getStore().getId(), YearMonth.from(run.getPeriodMonth()), null, actorId
        );
        return detail(recalculated);
    }

    @Transactional
    public PayrollRunDetailView approve(
            UUID storeId,
            UUID runId,
            long version,
            UUID actorId
    ) {
        PayrollRun run = requireLatestRun(requireRun(storeId, runId));
        requireVersion(run.getVersion(), version, "payroll run");
        freshnessService.requireCurrent(run);
        AppUser actor = requireActor(actorId);
        Map<String, Object> before = runStateSummary(run);
        if (run.getStatus() != PayrollRunStatus.CALCULATED
                || !run.isCalculationComplete()) {
            throw new PayrollStateConflictException(
                    "payroll run is not complete and calculated"
            );
        }
        run.approve(actor, clock.instant());
        runRepository.saveAndFlush(run);
        auditLogService.record(
                actorId,
                run.getStore().getId(),
                AuditAction.PAYROLL_APPROVED,
                new AuditTarget(AuditEntityType.PAYROLL_RUN, run.getId()),
                null,
                before,
                runStateSummary(run)
        );
        snapshotStore.appendEvent(new PayrollEvent(
                run, PayrollEventType.APPROVED, actor, null
        ));
        return detail(run);
    }

    @Transactional
    public PayrollRunDetailView markPaid(
            UUID storeId,
            UUID runId,
            long version,
            UUID actorId
    ) {
        PayrollRun run = requireLatestRun(requireRun(storeId, runId));
        requireVersion(run.getVersion(), version, "payroll run");
        freshnessService.requireCurrent(run);
        AppUser actor = requireActor(actorId);
        Map<String, Object> before = runStateSummary(run);
        if (run.getStatus() != PayrollRunStatus.APPROVED) {
            throw new PayrollStateConflictException(
                    "only approved payroll can be paid"
            );
        }
        run.markPaid(actor, clock.instant());
        runRepository.saveAndFlush(run);
        auditLogService.record(
                actorId,
                run.getStore().getId(),
                AuditAction.PAYROLL_PAID,
                new AuditTarget(AuditEntityType.PAYROLL_RUN, run.getId()),
                null,
                before,
                runStateSummary(run)
        );
        snapshotStore.appendEvent(new PayrollEvent(
                run, PayrollEventType.PAID, actor, null
        ));
        PayrollRunDetailView result = detail(run);
        reportFinalizationService.finalizePaidRun(run, result, actor);
        return result;
    }

    private Map<String, Object> adjustmentSummary(PayrollAdjustment adjustment) {
        return Map.of(
                "payrollRunId", adjustment.getPayrollRun().getId(),
                "employeeId", adjustment.getEmployee().getId(),
                "adjustmentType", adjustment.getAdjustmentType(),
                "amount", adjustment.getAmount(),
                "active", adjustment.isActive()
        );
    }

    private Map<String, Object> runStateSummary(PayrollRun run) {
        return Map.of(
                "periodMonth", run.getPeriodMonth(),
                "revision", run.getRevision(),
                "status", run.getStatus(),
                "calculationComplete", run.isCalculationComplete(),
                "version", run.getVersion()
        );
    }

    private PayrollRun requireEditableRun(UUID storeId, UUID runId, long version) {
        PayrollRun run = requireLatestRun(requireRun(storeId, runId));
        requireVersion(run.getVersion(), version, "payroll run");
        return run;
    }

    private PayrollRun requireLatestRun(PayrollRun run) {
        PayrollRun latest = runRepository
                .findFirstByStoreIdAndPeriodMonthOrderByRevisionDesc(
                        run.getStore().getId(), run.getPeriodMonth()
                ).orElseThrow(() -> new PayrollRunNotFoundException(run.getId()));
        if (!latest.getId().equals(run.getId())) {
            throw new PayrollStateConflictException("only the latest payroll revision can be changed");
        }
        return run;
    }

    private PayrollRun requireRun(UUID storeId, UUID runId) {
        UUID validatedStoreId = requireNonNull(storeId, "storeId");
        PayrollRun run = runRepository.findById(requireNonNull(runId, "runId"))
                .orElseThrow(() -> new PayrollRunNotFoundException(runId));
        if (!run.getStore().getId().equals(validatedStoreId)) {
            throw new PayrollRunNotFoundException(runId);
        }
        return run;
    }

    private AppUser requireActor(UUID actorId) {
        return userRepository.findById(requireNonNull(actorId, "actorId"))
                .orElseThrow(() -> new IllegalArgumentException("actor does not exist"));
    }

    private void requireVersion(long actual, long requested, String resource) {
        if (actual != requested) {
            throw new PayrollStateConflictException(resource + " was changed; reload and retry");
        }
    }

    private PayrollRunDetailView detail(PayrollRun run) {
        UUID runId = run.getId();
        return new PayrollRunDetailView(
                summary(run),
                scheme(run.getScheme()),
                poolRepository.findAllByPayrollRunIdOrderByWorkDate(runId).stream()
                        .map(this::pool).toList(),
                allocationRepository
                        .findAllByPayrollRunIdOrderByWorkDateEmployeeFullName(runId)
                        .stream().map(this::allocation).toList(),
                adjustmentRepository.findAllByPayrollRunIdOrderByCreatedAt(runId)
                        .stream().map(this::adjustment).toList(),
                statementRepository.findAllByPayrollRunIdOrderByEmployeeFullName(runId)
                        .stream().map(this::statement).toList(),
                eventRepository.findAllByPayrollRunIdOrderByCreatedAt(runId)
                        .stream().map(this::event).toList()
        );
    }

    private PayrollRunSummaryView summary(PayrollRun run) {
        return new PayrollRunSummaryView(
                run.getId(),
                run.getStore().getId(),
                run.getPeriodMonth(),
                run.getRevision(),
                id(run.getSupersedes()),
                run.getRevisionReason(),
                run.getStatus(),
                freshnessService.evaluate(run),
                run.getPlanResult(),
                run.isCalculationComplete(),
                run.getUnmappedItemCount(),
                run.getMissingCostItemCount(),
                run.getDaysWithoutShift(),
                id(run.getCreatedBy()),
                id(run.getApprovedBy()),
                run.getApprovedAt(),
                id(run.getPaidBy()),
                run.getPaidAt(),
                run.getVersion(),
                run.getCreatedAt(),
                run.getUpdatedAt()
        );
    }

    private PayrollSchemeView scheme(PayrollScheme scheme) {
        return new PayrollSchemeView(
                scheme.getId(),
                scheme.getCode(),
                scheme.getEffectiveFrom(),
                scheme.getAchievedPercentage(),
                scheme.getMissedPercentage(),
                scheme.getAchievedTier1Rate(),
                scheme.getMissedTier1Rate(),
                scheme.getAchievedTier2Rate(),
                scheme.getMissedTier2Rate(),
                scheme.getAdvanceAmount()
        );
    }

    private PayrollDailyPoolView pool(PayrollDailyPool pool) {
        return new PayrollDailyPoolView(
                pool.getId(),
                pool.getWorkDate(),
                pool.getAccessoryTurnover(),
                pool.getServiceTurnover(),
                pool.getPlaystationGrossProfit(),
                pool.getPaidRepairGrossProfit(),
                pool.getTier1Quantity(),
                pool.getTier2Quantity(),
                pool.getAccessoryPercentageRate(),
                pool.getServicePercentageRate(),
                pool.getTier1Rate(),
                pool.getTier2Rate(),
                pool.getAccessoryReward(),
                pool.getServiceReward(),
                pool.getPlaystationReward(),
                pool.getPaidRepairReward(),
                pool.getTier1Reward(),
                pool.getTier2Reward(),
                pool.getFundAmount(),
                pool.getShiftEmployeeCount(),
                pool.getUnmappedItemCount(),
                pool.getMissingCostItemCount(),
                pool.isCalculationComplete()
        );
    }

    private PayrollDailyAllocationView allocation(PayrollDailyAllocation allocation) {
        return new PayrollDailyAllocationView(
                allocation.getId(),
                allocation.getEmployee().getId(),
                allocation.getEmployee().getFullName(),
                allocation.getWorkDate(),
                allocation.getWorkedHours(),
                allocation.getAmount()
        );
    }

    private PayrollAdjustmentView adjustment(PayrollAdjustment adjustment) {
        return new PayrollAdjustmentView(
                adjustment.getId(),
                adjustment.getEmployee().getId(),
                adjustment.getEmployee().getFullName(),
                adjustment.getAdjustmentType(),
                adjustment.getAmount(),
                adjustment.getReason(),
                adjustment.isActive(),
                id(adjustment.getCreatedBy()),
                id(adjustment.getVoidedBy()),
                adjustment.getVoidReason(),
                adjustment.getVoidedAt(),
                adjustment.getVersion(),
                adjustment.getCreatedAt()
        );
    }

    private PayrollStatementView statement(PayrollStatement statement) {
        return new PayrollStatementView(
                statement.getId(),
                statement.getEmployee().getId(),
                statement.getEmployee().getFullName(),
                statement.getShiftCount(),
                statement.getWorkedHours(),
                statement.getEarnedAmount(),
                statement.getAdvanceAmount(),
                statement.getPenaltyAmount(),
                statement.getInventoryAmount(),
                statement.getTaxAmount(),
                statement.getPayableAmount()
        );
    }

    private PayrollEventView event(PayrollEvent event) {
        return new PayrollEventView(
                event.getId(),
                event.getEventType(),
                id(event.getActorUser()),
                event.getDetails(),
                event.getCreatedAt()
        );
    }

    private UUID id(com.storeanalytics.common.persistence.AbstractCreatedEntity entity) {
        return entity == null ? null : entity.getId();
    }
}
