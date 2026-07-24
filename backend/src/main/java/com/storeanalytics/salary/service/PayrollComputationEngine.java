package com.storeanalytics.salary.service;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.employee.model.Employee;
import com.storeanalytics.performance.model.EmployeeWorkShift;
import com.storeanalytics.salary.model.PayrollAppliedRates;
import com.storeanalytics.salary.model.PayrollDailyPoolAmounts;
import com.storeanalytics.salary.model.PayrollDailyPoolInput;
import com.storeanalytics.salary.model.PayrollPlanResult;
import com.storeanalytics.salary.model.PayrollPlanStatus;
import com.storeanalytics.salary.model.PayrollRunQuality;
import com.storeanalytics.salary.model.PayrollScheme;
import com.storeanalytics.salary.repository.PayrollDailySalesAggregate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

final class PayrollComputationEngine {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    PayrollComputationResult compute(PayrollCalculationSourceData sourceData) {
        return compute(sourceData, null);
    }

    PayrollComputationResult compute(
            PayrollCalculationSourceData sourceData,
            PayrollPlanStatus assumedStatus
    ) {
        PayrollCalculationSourceData source = requireNonNull(sourceData, "sourceData");
        PayrollPlanResult planResult = planResult(source);
        PayrollPlanStatus status = assumedStatus == null
                ? planResult.status() : assumedStatus;
        PayrollAppliedRates rates = rates(source.scheme(), status);
        Map<LocalDate, PayrollDailySalesAggregate> salesByDate = new HashMap<>();
        source.dailySales().forEach(day -> salesByDate.put(day.workDate(), day));
        Map<LocalDate, List<PayrollComputedShift>> shiftsByDate = shiftsByDate(source.shifts());
        Set<LocalDate> dates = new TreeSet<>();
        dates.addAll(salesByDate.keySet());
        dates.addAll(shiftsByDate.keySet());

        List<PayrollComputedDay> days = new ArrayList<>();
        Map<UUID, EmployeeAccumulator> employees = new LinkedHashMap<>();
        int unmapped = 0;
        int missingCost = 0;
        int withoutShift = 0;
        for (LocalDate date : dates) {
            PayrollDailySalesAggregate sales = salesByDate.getOrDefault(date, zeroSales(date));
            List<PayrollComputedShift> shifts = shiftsByDate.getOrDefault(date, List.of());
            PayrollDailyPoolInput input = input(sales);
            PayrollDailyPoolAmounts amounts = amounts(input, rates);
            unmapped += input.unmappedItemCount();
            missingCost += input.missingCostItemCount();
            if (shifts.isEmpty()
                    && amounts.fundAmount() != null
                    && amounts.fundAmount().signum() != 0) {
                withoutShift++;
            }
            shifts.forEach(shift -> employees
                    .computeIfAbsent(
                            shift.employee().getId(),
                            ignored -> new EmployeeAccumulator(shift.employee())
                    )
                    .addShift(shift.workedHours()));
            List<PayrollComputedAllocation> allocations = allocations(
                    shifts, amounts.fundAmount(), employees
            );
            days.add(new PayrollComputedDay(
                    input, amounts, List.copyOf(shifts), allocations
            ));
        }
        PayrollRunQuality quality = new PayrollRunQuality(
                unmapped == 0 && missingCost == 0 && withoutShift == 0,
                unmapped,
                missingCost,
                withoutShift
        );
        return new PayrollComputationResult(
                planResult,
                rates,
                quality,
                days,
                employees.values().stream().map(EmployeeAccumulator::result).toList()
        );
    }

    PayrollDailyPoolAmounts amounts(
            PayrollDailySalesAggregate sales,
            PayrollScheme scheme,
            PayrollPlanStatus status
    ) {
        return amounts(input(sales), rates(scheme, status));
    }

    private PayrollPlanResult planResult(PayrollCalculationSourceData source) {
        BigDecimal revenue = sum(source, PayrollDailySalesAggregate::netRevenue);
        BigDecimal accessories = sum(source, PayrollDailySalesAggregate::accessoryTurnover);
        BigDecimal services = sum(source, PayrollDailySalesAggregate::serviceTurnover);
        BigDecimal accessoryShare = share(accessories, revenue);
        BigDecimal serviceShare = share(services, revenue);
        BigDecimal accessoryTarget = source.plan().getAccessoryShareTarget();
        BigDecimal serviceTarget = source.plan().getServiceShareTarget();
        return new PayrollPlanResult(
                source.plan().getRevenueTarget(),
                revenue,
                revenue.compareTo(source.plan().getRevenueTarget()) >= 0,
                accessoryTarget,
                accessories,
                accessoryShare,
                shareAchieved(accessories, revenue, accessoryTarget),
                serviceTarget,
                services,
                serviceShare,
                shareAchieved(services, revenue, serviceTarget)
        );
    }

    private BigDecimal sum(
            PayrollCalculationSourceData source,
            java.util.function.Function<PayrollDailySalesAggregate, BigDecimal> getter
    ) {
        return source.dailySales().stream()
                .map(getter)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.UNNECESSARY);
    }

    private BigDecimal share(BigDecimal turnover, BigDecimal revenue) {
        return revenue.signum() <= 0
                ? null
                : turnover.multiply(ONE_HUNDRED)
                        .divide(revenue, 2, RoundingMode.HALF_UP);
    }

    private boolean shareAchieved(
            BigDecimal turnover,
            BigDecimal revenue,
            BigDecimal target
    ) {
        return revenue.signum() > 0
                && turnover.multiply(ONE_HUNDRED)
                        .compareTo(target.multiply(revenue)) >= 0;
    }

    private PayrollAppliedRates rates(PayrollScheme scheme, PayrollPlanStatus status) {
        PayrollScheme validatedScheme = requireNonNull(scheme, "scheme");
        PayrollPlanStatus validatedStatus = requireNonNull(status, "planStatus");
        return new PayrollAppliedRates(
                validatedStatus.accessoryAchieved()
                        ? validatedScheme.getAchievedPercentage()
                        : validatedScheme.getMissedPercentage(),
                validatedStatus.serviceAchieved()
                        ? validatedScheme.getAchievedPercentage()
                        : validatedScheme.getMissedPercentage(),
                validatedStatus.revenueAchieved()
                        ? validatedScheme.getAchievedTier1Rate()
                        : validatedScheme.getMissedTier1Rate(),
                validatedStatus.revenueAchieved()
                        ? validatedScheme.getAchievedTier2Rate()
                        : validatedScheme.getMissedTier2Rate()
        );
    }

    private PayrollDailyPoolInput input(PayrollDailySalesAggregate sales) {
        return new PayrollDailyPoolInput(
                sales.workDate(),
                money(sales.accessoryTurnover()),
                money(sales.serviceTurnover()),
                nullableMoney(sales.playstationGrossProfit()),
                nullableMoney(sales.paidRepairGrossProfit()),
                quantity(sales.tier1Quantity()),
                quantity(sales.tier2Quantity()),
                sales.unmappedItemCount(),
                sales.missingCostItemCount()
        );
    }

    private PayrollDailyPoolAmounts amounts(
            PayrollDailyPoolInput input,
            PayrollAppliedRates rates
    ) {
        BigDecimal accessoryReward = percent(
                input.accessoryTurnover(), rates.accessoryPercentage()
        );
        BigDecimal serviceReward = percent(
                input.serviceTurnover(), rates.servicePercentage()
        );
        BigDecimal playstationReward = input.playstationGrossProfit() == null
                ? null : percent(input.playstationGrossProfit(), rates.servicePercentage());
        BigDecimal repairReward = input.paidRepairGrossProfit() == null
                ? null : percent(input.paidRepairGrossProfit(), rates.servicePercentage());
        BigDecimal tier1Reward = money(input.tier1Quantity().multiply(rates.tier1Rate()));
        BigDecimal tier2Reward = money(input.tier2Quantity().multiply(rates.tier2Rate()));
        BigDecimal fund = playstationReward == null || repairReward == null
                || !input.complete()
                ? null
                : money(accessoryReward
                        .add(serviceReward)
                        .add(playstationReward)
                        .add(repairReward)
                        .add(tier1Reward)
                        .add(tier2Reward));
        return new PayrollDailyPoolAmounts(
                rates.accessoryPercentage(),
                rates.servicePercentage(),
                rates.tier1Rate(),
                rates.tier2Rate(),
                accessoryReward,
                serviceReward,
                playstationReward,
                repairReward,
                tier1Reward,
                tier2Reward,
                fund
        );
    }

    private List<PayrollComputedAllocation> allocations(
            List<PayrollComputedShift> shifts,
            BigDecimal fundAmount,
            Map<UUID, EmployeeAccumulator> accumulators
    ) {
        if (fundAmount == null || shifts.isEmpty()) {
            return List.of();
        }
        List<BigDecimal> shares = split(fundAmount, shifts.size());
        List<PayrollComputedAllocation> result = new ArrayList<>();
        for (int index = 0; index < shifts.size(); index++) {
            PayrollComputedShift shift = shifts.get(index);
            Employee employee = shift.employee();
            BigDecimal employeeShare = shares.get(index);
            result.add(new PayrollComputedAllocation(
                    employee, shift.workedHours(), employeeShare
            ));
            accumulators.get(employee.getId()).addEarned(employeeShare);
        }
        return result;
    }

    private Map<LocalDate, List<PayrollComputedShift>> shiftsByDate(
            List<EmployeeWorkShift> shifts
    ) {
        Map<LocalDate, List<PayrollComputedShift>> result = new LinkedHashMap<>();
        shifts.forEach(shift -> result
                .computeIfAbsent(shift.getWorkDate(), ignored -> new ArrayList<>())
                .add(new PayrollComputedShift(
                        shift.getEmployee(), shift.getWorkedHours()
                )));
        result.values().forEach(entries -> entries.sort(
                Comparator.comparing(entry -> entry.employee().getId().toString())
        ));
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

    private BigDecimal percent(BigDecimal value, BigDecimal percentage) {
        return money(value.multiply(percentage).divide(ONE_HUNDRED));
    }

    private BigDecimal money(BigDecimal value) {
        return requireNonNull(value, "money").setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal nullableMoney(BigDecimal value) {
        return value == null ? null : money(value);
    }

    private BigDecimal quantity(BigDecimal value) {
        return requireNonNull(value, "quantity").setScale(3, RoundingMode.UNNECESSARY);
    }

    private PayrollDailySalesAggregate zeroSales(LocalDate date) {
        BigDecimal money = money(BigDecimal.ZERO);
        BigDecimal quantity = quantity(BigDecimal.ZERO);
        return new PayrollDailySalesAggregate(
                date, money, money, money, money, money, quantity, quantity, 0, 0
        );
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

        private PayrollComputedEmployee result() {
            return new PayrollComputedEmployee(employee, shiftCount, workedHours, earned);
        }
    }
}
