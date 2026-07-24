package com.storeanalytics.salary.service;

import com.storeanalytics.employee.model.Employee;
import com.storeanalytics.salary.model.PayrollAdjustment;
import com.storeanalytics.salary.model.PayrollAdjustmentType;
import com.storeanalytics.salary.model.PayrollDailyPoolAmounts;
import com.storeanalytics.salary.model.PayrollDailyPoolInput;
import com.storeanalytics.salary.model.PayrollRun;
import com.storeanalytics.salary.model.PayrollScheme;
import com.storeanalytics.salary.repository.PayrollAdjustmentRepository;
import com.storeanalytics.salary.repository.PayrollRunRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PayrollPreviewService {

    private final PayrollCalculationSource source;
    private final PayrollReadinessService readinessService;
    private final PayrollRunRepository runRepository;
    private final PayrollAdjustmentRepository adjustmentRepository;
    private final PayrollComputationEngine engine = new PayrollComputationEngine();

    public PayrollPreviewService(
            PayrollCalculationSource source,
            PayrollReadinessService readinessService,
            PayrollRunRepository runRepository,
            PayrollAdjustmentRepository adjustmentRepository
    ) {
        this.source = source;
        this.readinessService = readinessService;
        this.runRepository = runRepository;
        this.adjustmentRepository = adjustmentRepository;
    }

    @Transactional(readOnly = true)
    public PayrollPreviewView preview(UUID storeId, YearMonth month) {
        PayrollCalculationSourceData sourceData = source.load(storeId, month);
        List<PayrollAdjustment> adjustments = activeAdjustments(storeId, month);
        PayrollComputationResult result = engine.compute(sourceData);
        return new PayrollPreviewView(
                sourceData.store().getId(),
                sourceData.plan().getPlanMonth(),
                false,
                result.planResult(),
                scheme(sourceData.scheme()),
                readinessService.inspect(storeId, month),
                scenario(result, sourceData.scheme(), adjustments)
        );
    }

    private List<PayrollAdjustment> activeAdjustments(UUID storeId, YearMonth month) {
        return runRepository.findFirstByStoreIdAndPeriodMonthOrderByRevisionDesc(
                        storeId, month.atDay(1)
                )
                .map(PayrollRun::getId)
                .map(adjustmentRepository::findAllByPayrollRunIdAndActiveTrue)
                .orElse(List.of());
    }

    private PayrollScenarioView scenario(
            PayrollComputationResult result,
            PayrollScheme scheme,
            List<PayrollAdjustment> adjustments
    ) {
        Map<UUID, AdjustmentTotals> totals = adjustmentTotals(adjustments);
        Map<UUID, PayrollComputedEmployee> computedEmployees = new LinkedHashMap<>();
        result.employees().forEach(employee -> computedEmployees.put(
                employee.employee().getId(), employee
        ));
        totals.values().forEach(total -> computedEmployees.computeIfAbsent(
                total.employee().getId(),
                ignored -> new PayrollComputedEmployee(
                        total.employee(),
                        0,
                        money(BigDecimal.ZERO),
                        money(BigDecimal.ZERO)
                )
        ));
        List<PayrollPreviewEmployeeView> employees = computedEmployees.values().stream()
                .map(employee -> employee(employee, totals, scheme))
                .sorted(Comparator.comparing(
                        PayrollPreviewEmployeeView::employeeName,
                        String.CASE_INSENSITIVE_ORDER
                ))
                .toList();
        BigDecimal totalPayable = employees.stream()
                .map(PayrollPreviewEmployeeView::payableAmount)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        return new PayrollScenarioView(
                result.appliedRates(),
                result.quality().complete(),
                totalFund(result.days()),
                money(totalPayable),
                result.days().stream().map(this::day).toList(),
                employees
        );
    }

    private PayrollPreviewEmployeeView employee(
            PayrollComputedEmployee computed,
            Map<UUID, AdjustmentTotals> totals,
            PayrollScheme scheme
    ) {
        AdjustmentTotals adjustment = totals.getOrDefault(
                computed.employee().getId(), AdjustmentTotals.zero(computed.employee())
        );
        BigDecimal advance = computed.shiftCount() == 0
                ? money(BigDecimal.ZERO) : scheme.getAdvanceAmount();
        BigDecimal payable = computed.earnedAmount()
                .subtract(advance)
                .subtract(adjustment.penalty())
                .subtract(adjustment.inventory())
                .subtract(adjustment.tax());
        return new PayrollPreviewEmployeeView(
                computed.employee().getId(),
                computed.employee().getFullName(),
                computed.shiftCount(),
                computed.workedHours(),
                computed.earnedAmount(),
                advance,
                adjustment.penalty(),
                adjustment.inventory(),
                adjustment.tax(),
                money(payable)
        );
    }

    private PayrollPreviewDayView day(PayrollComputedDay day) {
        PayrollDailyPoolInput input = day.input();
        PayrollDailyPoolAmounts amounts = day.amounts();
        return new PayrollPreviewDayView(
                input.workDate(),
                input.accessoryTurnover(),
                input.serviceTurnover(),
                input.playstationGrossProfit(),
                input.paidRepairGrossProfit(),
                input.tier1Quantity(),
                input.tier2Quantity(),
                amounts.accessoryReward(),
                amounts.serviceReward(),
                amounts.playstationReward(),
                amounts.paidRepairReward(),
                amounts.tier1Reward(),
                amounts.tier2Reward(),
                amounts.fundAmount(),
                day.shifts().size(),
                input.complete() && (amounts.fundAmount() == null
                        || !day.shifts().isEmpty()
                        || amounts.fundAmount().signum() == 0),
                day.allocations().stream().map(allocation -> new PayrollPreviewAllocationView(
                        allocation.employee().getId(),
                        allocation.employee().getFullName(),
                        allocation.workedHours(),
                        allocation.amount()
                )).toList()
        );
    }

    private BigDecimal totalFund(List<PayrollComputedDay> days) {
        if (days.stream().anyMatch(day -> day.amounts().fundAmount() == null)) {
            return null;
        }
        return money(days.stream()
                .map(day -> day.amounts().fundAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private Map<UUID, AdjustmentTotals> adjustmentTotals(List<PayrollAdjustment> adjustments) {
        Map<UUID, AdjustmentTotals> result = new HashMap<>();
        for (PayrollAdjustment adjustment : adjustments) {
            AdjustmentTotals current = result.getOrDefault(
                    adjustment.getEmployee().getId(),
                    AdjustmentTotals.zero(adjustment.getEmployee())
            );
            result.put(
                    adjustment.getEmployee().getId(),
                    current.add(adjustment.getAdjustmentType(), adjustment.getAmount())
            );
        }
        return result;
    }

    private PayrollSchemeView scheme(PayrollScheme scheme) {
        return new PayrollSchemeView(
                scheme.getId(), scheme.getCode(), scheme.getEffectiveFrom(),
                scheme.getAchievedPercentage(), scheme.getMissedPercentage(),
                scheme.getAchievedTier1Rate(), scheme.getMissedTier1Rate(),
                scheme.getAchievedTier2Rate(), scheme.getMissedTier2Rate(),
                scheme.getAdvanceAmount()
        );
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
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
