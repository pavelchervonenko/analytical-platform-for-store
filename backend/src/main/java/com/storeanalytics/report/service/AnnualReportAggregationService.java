package com.storeanalytics.report.service;

import com.storeanalytics.metrics.service.AttachRateEntry;
import com.storeanalytics.metrics.service.CategoryKpiEntry;
import com.storeanalytics.performance.service.EmployeeRatingEntry;
import com.storeanalytics.salary.service.PayrollStatementView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class AnnualReportAggregationService {

    AnnualReportAggregate aggregate(List<MonthlyReportPayload> months) {
        StoreAccumulator store = new StoreAccumulator();
        Map<String, CategoryAccumulator> categories = new LinkedHashMap<>();
        Map<String, AttachAccumulator> attachRates = new LinkedHashMap<>();
        Map<UUID, EmployeeAccumulator> employees = new LinkedHashMap<>();
        for (MonthlyReportPayload month : months) {
            store.add(month);
            addCategories(categories, month);
            addAttachRates(attachRates, month);
            addEmployees(employees, month);
        }
        return new AnnualReportAggregate(
                store.view(months.size()),
                categories.values().stream().map(CategoryAccumulator::view).toList(),
                attachRates.values().stream().map(AttachAccumulator::view).toList(),
                employees.values().stream()
                        .map(EmployeeAccumulator::view)
                        .sorted(Comparator.comparing(AnnualEmployeeTotals::employeeName))
                        .toList()
        );
    }

    private void addCategories(
            Map<String, CategoryAccumulator> target,
            MonthlyReportPayload month
    ) {
        for (CategoryKpiEntry category : month.categoryKpi().categories()) {
            target.computeIfAbsent(
                    category.categoryCode(),
                    ignored -> new CategoryAccumulator(
                            category.categoryCode(), category.categoryName()
                    )
            ).add(category);
        }
    }

    private void addAttachRates(
            Map<String, AttachAccumulator> target,
            MonthlyReportPayload month
    ) {
        for (AttachRateEntry rate : month.attachRates().rates()) {
            String formulaVersion = month.attachRates().formulaVersion();
            String aggregateKey = formulaVersion + ":" + rate.metricCode();
            target.computeIfAbsent(
                    aggregateKey,
                    ignored -> new AttachAccumulator(formulaVersion, rate.metricCode())
            ).add(rate);
        }
    }

    private void addEmployees(
            Map<UUID, EmployeeAccumulator> target,
            MonthlyReportPayload month
    ) {
        for (EmployeeRatingEntry employee : month.employeeRating().employees()) {
            target.computeIfAbsent(
                    employee.employeeId(),
                    ignored -> new EmployeeAccumulator(
                            employee.employeeId(), employee.displayName()
                    )
            ).addRating(employee);
        }
        for (PayrollStatementView statement : month.payroll().statements()) {
            target.computeIfAbsent(
                    statement.employeeId(),
                    ignored -> new EmployeeAccumulator(
                            statement.employeeId(), statement.employeeName()
                    )
            ).addPayroll(statement);
        }
    }

    private static BigDecimal sum(BigDecimal total, BigDecimal value) {
        return value == null ? total : total.add(value);
    }

    private static BigDecimal percentage(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() <= 0) {
            return null;
        }
        return numerator.multiply(BigDecimal.valueOf(100))
                .divide(denominator, 2, RoundingMode.HALF_UP);
    }

    private static final class StoreAccumulator {

        private BigDecimal revenue = BigDecimal.ZERO;
        private BigDecimal quantity = BigDecimal.ZERO;
        private BigDecimal cost = BigDecimal.ZERO;
        private BigDecimal grossProfit = BigDecimal.ZERO;
        private BigDecimal payrollEarned = BigDecimal.ZERO;
        private BigDecimal payrollPayable = BigDecimal.ZERO;
        private boolean completeCost = true;

        void add(MonthlyReportPayload month) {
            revenue = sum(revenue, month.storeKpi().netRevenue());
            quantity = sum(quantity, month.storeKpi().netQuantity());
            if (month.storeKpi().costAmount() == null
                    || month.storeKpi().grossProfit() == null) {
                completeCost = false;
            } else {
                cost = cost.add(month.storeKpi().costAmount());
                grossProfit = grossProfit.add(month.storeKpi().grossProfit());
            }
            for (PayrollStatementView statement : month.payroll().statements()) {
                payrollEarned = sum(payrollEarned, statement.earnedAmount());
                payrollPayable = sum(payrollPayable, statement.payableAmount());
            }
        }

        AnnualStoreTotals view(int monthCount) {
            BigDecimal safeCost = completeCost ? cost : null;
            BigDecimal safeGrossProfit = completeCost ? grossProfit : null;
            return new AnnualStoreTotals(
                    monthCount,
                    revenue,
                    quantity,
                    safeCost,
                    safeGrossProfit,
                    percentage(safeGrossProfit, revenue),
                    payrollEarned,
                    payrollPayable
            );
        }
    }

    private static final class CategoryAccumulator {

        private final String code;
        private String name;
        private BigDecimal revenue = BigDecimal.ZERO;
        private BigDecimal quantity = BigDecimal.ZERO;
        private BigDecimal cost = BigDecimal.ZERO;
        private BigDecimal grossProfit = BigDecimal.ZERO;
        private boolean completeCost = true;

        CategoryAccumulator(String code, String name) {
            this.code = code;
            this.name = name;
        }

        void add(CategoryKpiEntry category) {
            name = category.categoryName();
            revenue = sum(revenue, category.metrics().netRevenue());
            quantity = sum(quantity, category.metrics().netQuantity());
            if (category.metrics().costAmount() == null
                    || category.metrics().grossProfit() == null) {
                completeCost = false;
            } else {
                cost = cost.add(category.metrics().costAmount());
                grossProfit = grossProfit.add(category.metrics().grossProfit());
            }
        }

        AnnualCategoryTotals view() {
            BigDecimal safeCost = completeCost ? cost : null;
            BigDecimal safeGrossProfit = completeCost ? grossProfit : null;
            return new AnnualCategoryTotals(
                    code,
                    name,
                    revenue,
                    quantity,
                    safeCost,
                    safeGrossProfit,
                    percentage(safeGrossProfit, revenue)
            );
        }
    }

    private static final class AttachAccumulator {

        private final String formulaVersion;
        private final String code;
        private BigDecimal numerator = BigDecimal.ZERO;
        private BigDecimal denominator = BigDecimal.ZERO;

        AttachAccumulator(String formulaVersion, String code) {
            this.formulaVersion = formulaVersion;
            this.code = code;
        }

        void add(AttachRateEntry rate) {
            numerator = sum(numerator, rate.numeratorReceiptCount());
            denominator = sum(denominator, rate.denominatorReceiptCount());
        }

        AnnualAttachRateTotals view() {
            return new AnnualAttachRateTotals(
                    formulaVersion,
                    code,
                    numerator,
                    denominator,
                    percentage(numerator, denominator)
            );
        }
    }

    private static final class EmployeeAccumulator {

        private final UUID id;
        private String name;
        private long shiftCount;
        private BigDecimal hours = BigDecimal.ZERO;
        private BigDecimal revenue = BigDecimal.ZERO;
        private BigDecimal earned = BigDecimal.ZERO;
        private BigDecimal payable = BigDecimal.ZERO;

        EmployeeAccumulator(UUID id, String name) {
            this.id = id;
            this.name = name;
        }

        void addRating(EmployeeRatingEntry employee) {
            name = employee.displayName();
            shiftCount += employee.shiftCount();
            hours = sum(hours, employee.workedHours());
            revenue = sum(revenue, employee.netRevenue());
        }

        void addPayroll(PayrollStatementView statement) {
            name = statement.employeeName();
            earned = sum(earned, statement.earnedAmount());
            payable = sum(payable, statement.payableAmount());
        }

        AnnualEmployeeTotals view() {
            return new AnnualEmployeeTotals(
                    id, name, shiftCount, hours, revenue, earned, payable
            );
        }
    }
}
