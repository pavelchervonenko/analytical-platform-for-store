package com.storeanalytics.salary.service;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import com.storeanalytics.audit.service.AuditAction;
import com.storeanalytics.audit.service.AuditEntityType;
import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.audit.service.AuditTarget;
import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.employee.model.Employee;
import com.storeanalytics.salary.model.PayrollAdjustment;
import com.storeanalytics.salary.model.PayrollAdjustmentType;
import com.storeanalytics.salary.model.PayrollDailyAllocation;
import com.storeanalytics.salary.model.PayrollDailyPool;
import com.storeanalytics.salary.model.PayrollDailyPoolAmounts;
import com.storeanalytics.salary.model.PayrollDailyPoolInput;
import com.storeanalytics.salary.model.PayrollEvent;
import com.storeanalytics.salary.model.PayrollEventType;
import com.storeanalytics.salary.model.PayrollRun;
import com.storeanalytics.salary.model.PayrollRunDefinition;
import com.storeanalytics.salary.model.PayrollPlanResult;
import com.storeanalytics.salary.model.PayrollRunQuality;
import com.storeanalytics.salary.model.PayrollRunStatus;
import com.storeanalytics.salary.model.PayrollScheme;
import com.storeanalytics.salary.model.PayrollStatement;
import com.storeanalytics.salary.model.PayrollStatementAmounts;
import com.storeanalytics.salary.repository.PayrollRunRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PayrollCalculationService {

    private final PayrollCalculationSource source;
    private final PayrollSourceFingerprintService fingerprintService;
    private final AppUserRepository userRepository;
    private final PayrollRunRepository runRepository;
    private final PayrollSnapshotStore snapshotStore;
    private final AuditLogService auditLogService;

    public PayrollCalculationService(
            PayrollCalculationSource source,
            PayrollSourceFingerprintService fingerprintService,
            AppUserRepository userRepository,
            PayrollRunRepository runRepository,
            PayrollSnapshotStore snapshotStore,
            AuditLogService auditLogService
    ) {
        this.source = source;
        this.fingerprintService = fingerprintService;
        this.userRepository = userRepository;
        this.runRepository = runRepository;
        this.snapshotStore = snapshotStore;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public PayrollRun calculate(
            UUID storeId,
            YearMonth month,
            String revisionReason,
            UUID actorId
    ) {
        PayrollCalculationSourceData sourceData = source.load(storeId, month);
        AppUser actor = requireActor(actorId);
        PreparedCalculation prepared = prepare(sourceData);
        PayrollRun latest = runRepository
                .findFirstByStoreIdAndPeriodMonthOrderByRevisionDesc(
                        sourceData.store().getId(), month.atDay(1)
                ).orElse(null);
        Map<String, Object> before = latest == null ? null : runSummary(latest);
        PayrollRun run;
        PayrollEventType eventType;
        if (latest == null) {
            run = new PayrollRun(definition(
                    sourceData, prepared, 1, null, null, actor
            ));
            eventType = PayrollEventType.CALCULATED;
        } else if (latest.getStatus() == PayrollRunStatus.CALCULATED) {
            run = latest;
            run.recalculate(definition(
                    sourceData,
                    prepared,
                    latest.getRevision(),
                    latest.getSupersedes(),
                    latest.getRevisionReason(),
                    actor
            ));
            eventType = PayrollEventType.RECALCULATED;
        } else {
            run = new PayrollRun(definition(
                    sourceData,
                    prepared,
                    latest.getRevision() + 1,
                    latest,
                    requireText(revisionReason, "revisionReason"),
                    actor
            ));
            eventType = PayrollEventType.REVISION_CREATED;
        }
        run = runRepository.saveAndFlush(run);
        if (eventType == PayrollEventType.REVISION_CREATED) {
            PayrollRun previous = requireNonNull(latest, "supersededPayrollRun");
            PayrollRun revisionRun = run;
            List<PayrollAdjustment> carriedAdjustments = snapshotStore
                    .activeAdjustments(previous).stream()
                    .map(adjustment -> new PayrollAdjustment(
                            revisionRun,
                            adjustment.getEmployee(),
                            adjustment.getAdjustmentType(),
                            adjustment.getAmount(),
                            "\u041f\u0435\u0440\u0435\u043d\u0435\u0441\u0435\u043d\u043e \u0438\u0437 "
                                    + "\u0440\u0435\u0432\u0438\u0437\u0438\u0438 " + previous.getRevision()
                                    + ": " + adjustment.getReason(),
                            actor
                    ))
                    .toList();
            snapshotStore.saveAdjustments(carriedAdjustments);
        }
        CalculatedEntities entities = materialize(run, prepared, sourceData.scheme());
        snapshotStore.replaceCalculatedSnapshot(
                run, entities.pools(), entities.allocations(), entities.statements()
        );
        snapshotStore.appendEvent(new PayrollEvent(run, eventType, actor, revisionReason));
        auditLogService.record(
                actorId,
                sourceData.store().getId(),
                auditAction(eventType),
                new AuditTarget(AuditEntityType.PAYROLL_RUN, run.getId()),
                revisionReason,
                before,
                runSummary(run)
        );
        return run;
    }

    private AuditAction auditAction(PayrollEventType eventType) {
        return switch (eventType) {
            case CALCULATED -> AuditAction.PAYROLL_CALCULATED;
            case RECALCULATED -> AuditAction.PAYROLL_RECALCULATED;
            case REVISION_CREATED -> AuditAction.PAYROLL_REVISION_CREATED;
            default -> throw new IllegalArgumentException(
                    "unsupported payroll calculation event " + eventType
            );
        };
    }

    private Map<String, Object> runSummary(PayrollRun run) {
        com.storeanalytics.salary.model.PayrollPlanResult plan = run.getPlanResult();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("periodMonth", run.getPeriodMonth());
        result.put("revision", run.getRevision());
        result.put("status", run.getStatus());
        result.put("schemeCode", run.getScheme().getCode());
        result.put("revenueTarget", plan.revenueTarget());
        result.put("actualRevenue", plan.actualRevenue());
        result.put("revenuePlanAchieved", plan.revenueAchieved());
        result.put("accessoryShareTarget", plan.accessoryShareTarget());
        result.put("actualAccessorySharePercent", plan.actualAccessorySharePercent());
        result.put("accessoryPlanAchieved", plan.accessoryAchieved());
        result.put("serviceShareTarget", plan.serviceShareTarget());
        result.put("actualServiceSharePercent", plan.actualServiceSharePercent());
        result.put("servicePlanAchieved", plan.serviceAchieved());
        result.put("calculationComplete", run.isCalculationComplete());
        result.put("unmappedItemCount", run.getUnmappedItemCount());
        result.put("missingCostItemCount", run.getMissingCostItemCount());
        result.put("daysWithoutShift", run.getDaysWithoutShift());
        return result;
    }

    private PreparedCalculation prepare(PayrollCalculationSourceData sourceData) {
        PayrollComputationResult computed = new PayrollComputationEngine().compute(sourceData);
        List<PreparedDay> days = computed.days().stream()
                .map(day -> new PreparedDay(
                        day.input(), day.amounts(), day.shifts()
                ))
                .toList();
        return new PreparedCalculation(
                computed.planResult(), computed.quality(), days
        );
    }
    private PayrollRunDefinition definition(
            PayrollCalculationSourceData sourceData,
            PreparedCalculation prepared,
            int revision,
            PayrollRun supersedes,
            String revisionReason,
            AppUser actor
    ) {
        return new PayrollRunDefinition(
                sourceData.store(),
                sourceData.plan().getPlanMonth(),
                revision,
                supersedes,
                revisionReason,
                sourceData.scheme(),
                prepared.planResult(),
                prepared.quality(),
                fingerprintService.capture(sourceData),
                actor
        );
    }

    private CalculatedEntities materialize(
            PayrollRun run,
            PreparedCalculation prepared,
            PayrollScheme scheme
    ) {
        List<PayrollDailyPool> pools = new ArrayList<>();
        List<PayrollDailyAllocation> allocations = new ArrayList<>();
        Map<UUID, EmployeeAccumulator> employees = new LinkedHashMap<>();
        for (PreparedDay day : prepared.days()) {
            PayrollDailyPool pool = new PayrollDailyPool(
                    run, day.input(), day.amounts(), day.shifts().size()
            );
            pools.add(pool);
            day.shifts().forEach(shift -> employees
                    .computeIfAbsent(
                            shift.employee().getId(),
                            ignored -> new EmployeeAccumulator(shift.employee())
                    )
                    .addShift(shift.workedHours()));
            if (day.amounts().fundAmount() != null && !day.shifts().isEmpty()) {
                List<BigDecimal> shares = split(
                        day.amounts().fundAmount(), day.shifts().size()
                );
                for (int index = 0; index < day.shifts().size(); index++) {
                    PayrollComputedShift shift = day.shifts().get(index);
                    Employee employee = shift.employee();
                    BigDecimal share = shares.get(index);
                    allocations.add(new PayrollDailyAllocation(
                            run,
                            employee,
                            day.input().workDate(),
                            shift.workedHours(),
                            share
                    ));
                    employees.get(employee.getId()).addEarned(share);
                }
            }
        }
        Map<UUID, AdjustmentTotals> adjustments = adjustmentTotals(
                snapshotStore.activeAdjustments(run)
        );
        adjustments.values().forEach(total -> employees.computeIfAbsent(
                total.employee().getId(), ignored -> new EmployeeAccumulator(total.employee())
        ));
        List<PayrollStatement> statements = employees.values().stream()
                .map(employee -> statement(
                        run,
                        employee,
                        adjustments.getOrDefault(
                                employee.employee().getId(),
                                AdjustmentTotals.zero(employee.employee())
                        ),
                        scheme
                ))
                .toList();
        return new CalculatedEntities(pools, allocations, statements);
    }

    private PayrollStatement statement(
            PayrollRun run,
            EmployeeAccumulator employee,
            AdjustmentTotals adjustments,
            PayrollScheme scheme
    ) {
        BigDecimal advance = employee.shiftCount() == 0
                ? money(BigDecimal.ZERO) : scheme.getAdvanceAmount();
        BigDecimal payable = employee.earned()
                .subtract(advance)
                .subtract(adjustments.penalty())
                .subtract(adjustments.inventory())
                .subtract(adjustments.tax());
        return new PayrollStatement(
                run,
                employee.employee(),
                employee.shiftCount(),
                employee.workedHours(),
                new PayrollStatementAmounts(
                        employee.earned(),
                        advance,
                        adjustments.penalty(),
                        adjustments.inventory(),
                        adjustments.tax(),
                        money(payable)
                )
        );
    }

    private Map<UUID, AdjustmentTotals> adjustmentTotals(List<PayrollAdjustment> adjustments) {
        Map<UUID, AdjustmentTotals> result = new HashMap<>();
        for (PayrollAdjustment adjustment : adjustments) {
            AdjustmentTotals totals = result.computeIfAbsent(
                    adjustment.getEmployee().getId(),
                    ignored -> AdjustmentTotals.zero(adjustment.getEmployee())
            );
            result.put(adjustment.getEmployee().getId(), totals.add(
                    adjustment.getAdjustmentType(), adjustment.getAmount()
            ));
        }
        return result;
    }

    private List<BigDecimal> split(BigDecimal amount, int count) {
        long cents = money(amount).movePointRight(2).longValueExact();
        long base = cents / count;
        long remainder = cents % count;
        List<BigDecimal> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            long extra = index < Math.abs(remainder) ? Long.signum(remainder) : 0;
            result.add(BigDecimal.valueOf(base + extra, 2));
        }
        return result;
    }

    private BigDecimal money(BigDecimal value) {
        return requireNonNull(value, "money").setScale(2, RoundingMode.HALF_UP);
    }

    private AppUser requireActor(UUID actorId) {
        UUID validated = requireNonNull(actorId, "actorId");
        return userRepository.findById(validated)
                .orElseThrow(() -> new IllegalArgumentException("actor does not exist"));
    }

    private record PreparedDay(
            PayrollDailyPoolInput input,
            PayrollDailyPoolAmounts amounts,
            List<PayrollComputedShift> shifts
    ) {
    }

    private record PreparedCalculation(
            PayrollPlanResult planResult,
            PayrollRunQuality quality,
            List<PreparedDay> days
    ) {
    }

    private record CalculatedEntities(
            List<PayrollDailyPool> pools,
            List<PayrollDailyAllocation> allocations,
            List<PayrollStatement> statements
    ) {
    }

    private static final class EmployeeAccumulator {

        private final Employee employee;
        private int shiftCount;
        private BigDecimal workedHours = BigDecimal.ZERO.setScale(2);
        private BigDecimal earned = BigDecimal.ZERO.setScale(2);

        private EmployeeAccumulator(Employee employee) {
            this.employee = employee;
        }

        private void addShift(BigDecimal hours) {
            shiftCount++;
            workedHours = workedHours.add(hours);
        }

        private void addEarned(BigDecimal amount) {
            earned = earned.add(amount);
        }

        private Employee employee() {
            return employee;
        }

        private int shiftCount() {
            return shiftCount;
        }

        private BigDecimal workedHours() {
            return workedHours;
        }

        private BigDecimal earned() {
            return earned;
        }
    }

    private record AdjustmentTotals(
            Employee employee,
            BigDecimal penalty,
            BigDecimal inventory,
            BigDecimal tax
    ) {

        private static AdjustmentTotals zero(Employee employee) {
            BigDecimal zero = BigDecimal.ZERO.setScale(2);
            return new AdjustmentTotals(employee, zero, zero, zero);
        }

        private AdjustmentTotals add(PayrollAdjustmentType type, BigDecimal amount) {
            return switch (type) {
                case PENALTY -> new AdjustmentTotals(
                        employee, penalty.add(amount), inventory, tax
                );
                case INVENTORY -> new AdjustmentTotals(
                        employee, penalty, inventory.add(amount), tax
                );
                case TAX -> new AdjustmentTotals(
                        employee, penalty, inventory, tax.add(amount)
                );
            };
        }
    }
}
