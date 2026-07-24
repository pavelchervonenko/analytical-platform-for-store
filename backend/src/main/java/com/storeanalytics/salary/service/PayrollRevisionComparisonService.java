package com.storeanalytics.salary.service;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.common.exception.InvalidRequestException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PayrollRevisionComparisonService {

    private final PayrollManagementService payrollService;

    public PayrollRevisionComparisonService(PayrollManagementService payrollService) {
        this.payrollService = payrollService;
    }

    @Transactional(readOnly = true)
    public PayrollRevisionComparisonView compare(
            UUID storeId,
            UUID previousRunId,
            UUID currentRunId
    ) {
        UUID validatedStoreId = requireNonNull(storeId, "storeId");
        PayrollRunDetailView previous = payrollService.get(
                validatedStoreId, requireNonNull(previousRunId, "previousRunId")
        );
        PayrollRunDetailView current = payrollService.get(
                validatedStoreId, requireNonNull(currentRunId, "currentRunId")
        );
        if (!previous.run().periodMonth().equals(current.run().periodMonth())) {
            throw new InvalidRequestException("payroll revisions must belong to the same month");
        }
        if (previous.run().id().equals(current.run().id())) {
            throw new InvalidRequestException("payroll revisions must be different");
        }
        BigDecimal previousFund = totalFund(previous.dailyPools());
        BigDecimal currentFund = totalFund(current.dailyPools());
        BigDecimal previousPayable = totalPayable(previous.statements());
        BigDecimal currentPayable = totalPayable(current.statements());
        return new PayrollRevisionComparisonView(
                validatedStoreId,
                previous.run().periodMonth(),
                previous.run(),
                current.run(),
                previous.run().planResult().revenueAchieved()
                        != current.run().planResult().revenueAchieved(),
                previous.run().planResult().accessoryAchieved()
                        != current.run().planResult().accessoryAchieved(),
                previous.run().planResult().serviceAchieved()
                        != current.run().planResult().serviceAchieved(),
                !previous.scheme().id().equals(current.scheme().id()),
                previousFund,
                currentFund,
                difference(currentFund, previousFund),
                previousPayable,
                currentPayable,
                difference(currentPayable, previousPayable),
                employeeChanges(previous, current),
                dayChanges(previous, current)
        );
    }

    private List<PayrollEmployeeRevisionChange> employeeChanges(
            PayrollRunDetailView previous,
            PayrollRunDetailView current
    ) {
        Map<UUID, PayrollStatementView> previousByEmployee = statements(previous);
        Map<UUID, PayrollStatementView> currentByEmployee = statements(current);
        Set<UUID> employeeIds = new LinkedHashSet<>();
        employeeIds.addAll(previousByEmployee.keySet());
        employeeIds.addAll(currentByEmployee.keySet());
        return employeeIds.stream()
                .map(employeeId -> employeeChange(
                        previousByEmployee.get(employeeId),
                        currentByEmployee.get(employeeId)
                ))
                .filter(change -> !change.reasons().isEmpty())
                .sorted(Comparator.comparing(
                        PayrollEmployeeRevisionChange::employeeName,
                        String.CASE_INSENSITIVE_ORDER
                ))
                .toList();
    }

    private PayrollEmployeeRevisionChange employeeChange(
            PayrollStatementView previous,
            PayrollStatementView current
    ) {
        PayrollStatementView identity = current == null ? previous : current;
        BigDecimal previousEarned = previous == null ? zero() : previous.earnedAmount();
        BigDecimal currentEarned = current == null ? zero() : current.earnedAmount();
        BigDecimal previousPayable = previous == null ? zero() : previous.payableAmount();
        BigDecimal currentPayable = current == null ? zero() : current.payableAmount();
        BigDecimal previousDeductions = previous == null ? zero() : deductions(previous);
        BigDecimal currentDeductions = current == null ? zero() : deductions(current);
        List<String> reasons = new ArrayList<>();
        if (previous == null || current == null
                || previous.shiftCount() != current.shiftCount()
                || different(previous.workedHours(), current.workedHours())) {
            reasons.add("SHIFT_CHANGED");
        }
        if (different(previousEarned, currentEarned)) {
            reasons.add("DAILY_ALLOCATION_CHANGED");
        }
        if (different(previousDeductions, currentDeductions)) {
            reasons.add("ADJUSTMENT_CHANGED");
        }
        if (previous != null && current != null
                && different(previous.advanceAmount(), current.advanceAmount())) {
            reasons.add("ADVANCE_CHANGED");
        }
        return new PayrollEmployeeRevisionChange(
                identity.employeeId(),
                identity.employeeName(),
                previousEarned,
                currentEarned,
                difference(currentEarned, previousEarned),
                previousPayable,
                currentPayable,
                difference(currentPayable, previousPayable),
                previousDeductions,
                currentDeductions,
                difference(currentDeductions, previousDeductions),
                reasons
        );
    }

    private List<PayrollDayRevisionChange> dayChanges(
            PayrollRunDetailView previous,
            PayrollRunDetailView current
    ) {
        Map<LocalDate, PayrollDailyPoolView> previousByDate = days(previous);
        Map<LocalDate, PayrollDailyPoolView> currentByDate = days(current);
        Set<LocalDate> dates = new LinkedHashSet<>();
        dates.addAll(previousByDate.keySet());
        dates.addAll(currentByDate.keySet());
        return dates.stream()
                .sorted()
                .map(date -> dayChange(
                        date, previousByDate.get(date), currentByDate.get(date)
                ))
                .filter(change -> !change.reasons().isEmpty())
                .toList();
    }

    private PayrollDayRevisionChange dayChange(
            LocalDate date,
            PayrollDailyPoolView previous,
            PayrollDailyPoolView current
    ) {
        BigDecimal previousFund = previous == null ? zero() : previous.fundAmount();
        BigDecimal currentFund = current == null ? zero() : current.fundAmount();
        int previousShifts = previous == null ? 0 : previous.shiftEmployeeCount();
        int currentShifts = current == null ? 0 : current.shiftEmployeeCount();
        List<String> reasons = new ArrayList<>();
        if (previous == null || current == null || inputsChanged(previous, current)) {
            reasons.add("SALES_RETURNS_OR_CLASSIFICATION_CHANGED");
        }
        if (previousShifts != currentShifts) {
            reasons.add("SHIFT_CHANGED");
        }
        if (previous != null && current != null && ratesChanged(previous, current)) {
            reasons.add("PLAN_STATUS_OR_FORMULA_CHANGED");
        }
        if (reasons.isEmpty() && different(previousFund, currentFund)) {
            reasons.add("FUND_CHANGED");
        }
        return new PayrollDayRevisionChange(
                date,
                previousFund,
                currentFund,
                difference(currentFund, previousFund),
                previousShifts,
                currentShifts,
                reasons
        );
    }

    private boolean inputsChanged(PayrollDailyPoolView previous, PayrollDailyPoolView current) {
        return different(previous.accessoryTurnover(), current.accessoryTurnover())
                || different(previous.serviceTurnover(), current.serviceTurnover())
                || different(previous.playstationGrossProfit(), current.playstationGrossProfit())
                || different(previous.paidRepairGrossProfit(), current.paidRepairGrossProfit())
                || different(previous.tier1Quantity(), current.tier1Quantity())
                || different(previous.tier2Quantity(), current.tier2Quantity())
                || previous.unmappedItemCount() != current.unmappedItemCount()
                || previous.missingCostItemCount() != current.missingCostItemCount();
    }

    private boolean ratesChanged(PayrollDailyPoolView previous, PayrollDailyPoolView current) {
        return different(
                        previous.accessoryPercentageRate(),
                        current.accessoryPercentageRate()
                )
                || different(
                        previous.servicePercentageRate(),
                        current.servicePercentageRate()
                )
                || different(previous.tier1Rate(), current.tier1Rate())
                || different(previous.tier2Rate(), current.tier2Rate());
    }

    private Map<UUID, PayrollStatementView> statements(PayrollRunDetailView detail) {
        Map<UUID, PayrollStatementView> result = new HashMap<>();
        detail.statements().forEach(value -> result.put(value.employeeId(), value));
        return result;
    }

    private Map<LocalDate, PayrollDailyPoolView> days(PayrollRunDetailView detail) {
        Map<LocalDate, PayrollDailyPoolView> result = new HashMap<>();
        detail.dailyPools().forEach(value -> result.put(value.workDate(), value));
        return result;
    }

    private BigDecimal totalFund(List<PayrollDailyPoolView> days) {
        if (days.stream().anyMatch(day -> day.fundAmount() == null)) {
            return null;
        }
        return money(days.stream().map(PayrollDailyPoolView::fundAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private BigDecimal totalPayable(List<PayrollStatementView> statements) {
        return money(statements.stream().map(PayrollStatementView::payableAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private BigDecimal deductions(PayrollStatementView statement) {
        return money(statement.penaltyAmount()
                .add(statement.inventoryAmount())
                .add(statement.taxAmount()));
    }

    private boolean different(BigDecimal first, BigDecimal second) {
        return first == null && second != null
                || first != null && second == null
                || first != null && first.compareTo(second) != 0;
    }

    private BigDecimal difference(BigDecimal current, BigDecimal previous) {
        return current == null || previous == null
                ? null : money(current.subtract(previous));
    }

    private BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
