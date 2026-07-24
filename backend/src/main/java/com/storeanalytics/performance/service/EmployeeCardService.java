package com.storeanalytics.performance.service;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.performance.exception.EmployeeAssignmentNotFoundException;
import com.storeanalytics.salary.exception.PayrollMonthNotCalculatedException;
import com.storeanalytics.salary.service.PayrollManagementService;
import com.storeanalytics.salary.service.PayrollRunDetailView;
import com.storeanalytics.salary.service.PayrollStatementView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
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
public class EmployeeCardService {

    private static final int SCALE = 2;

    private final EmployeeRatingQueryService ratingService;
    private final PayrollManagementService payrollService;

    public EmployeeCardService(
            EmployeeRatingQueryService ratingService,
            PayrollManagementService payrollService
    ) {
        this.ratingService = ratingService;
        this.payrollService = payrollService;
    }

    @Transactional(readOnly = true)
    public EmployeeDirectoryView directory(UUID storeId, StoreKpiPeriod period) {
        UUID validatedStoreId = requireNonNull(storeId, "storeId");
        StoreKpiPeriod currentPeriod = requireNonNull(period, "period");
        StoreKpiPeriod previousPeriod = previous(currentPeriod);
        EmployeeRatingResult current = ratingService.get(validatedStoreId, currentPeriod);
        EmployeeRatingResult prior = ratingService.get(validatedStoreId, previousPeriod);
        Map<UUID, EmployeeRatingEntry> previousByEmployee = byEmployee(prior.employees());
        List<EmployeeDirectoryEntry> employees = current.employees().stream()
                .map(entry -> new EmployeeDirectoryEntry(
                        entry,
                        dynamics(entry, previousByEmployee.get(entry.employeeId()))
                ))
                .sorted(directoryOrder())
                .toList();
        return new EmployeeDirectoryView(
                validatedStoreId,
                currentPeriod.start(),
                currentPeriod.end(),
                previousPeriod.start(),
                previousPeriod.end(),
                employees
        );
    }

    @Transactional(readOnly = true)
    public EmployeeCardView card(
            UUID storeId,
            UUID employeeId,
            StoreKpiPeriod period
    ) {
        UUID validatedStoreId = requireNonNull(storeId, "storeId");
        UUID validatedEmployeeId = requireNonNull(employeeId, "employeeId");
        StoreKpiPeriod currentPeriod = requireNonNull(period, "period");
        StoreKpiPeriod previousPeriod = previous(currentPeriod);
        EmployeeRatingResult currentResult = ratingService.get(
                validatedStoreId, currentPeriod
        );
        EmployeeRatingEntry current = find(
                currentResult.employees(), validatedStoreId, validatedEmployeeId
        );
        EmployeeRatingResult previousResult = ratingService.get(
                validatedStoreId, previousPeriod
        );
        EmployeeRatingEntry prior = byEmployee(previousResult.employees())
                .get(validatedEmployeeId);
        return new EmployeeCardView(
                validatedStoreId,
                validatedEmployeeId,
                currentPeriod.start(),
                currentPeriod.end(),
                previousPeriod.start(),
                previousPeriod.end(),
                currentResult.formula(),
                currentResult.plan(),
                current,
                prior,
                dynamics(current, prior),
                payroll(validatedStoreId, validatedEmployeeId, currentPeriod)
        );
    }

    private EmployeePayrollContextView payroll(
            UUID storeId,
            UUID employeeId,
            StoreKpiPeriod period
    ) {
        YearMonth month = YearMonth.from(period.start());
        if (!period.start().equals(month.atDay(1))
                || !period.end().equals(month.atEndOfMonth())) {
            return null;
        }
        try {
            PayrollRunDetailView payroll = payrollService.latest(storeId, month);
            PayrollStatementView statement = payroll.statements().stream()
                    .filter(value -> value.employeeId().equals(employeeId))
                    .findFirst()
                    .orElse(null);
            return statement == null
                    ? null : new EmployeePayrollContextView(payroll.run(), statement);
        } catch (PayrollMonthNotCalculatedException exception) {
            return null;
        }
    }

    private EmployeeRatingDynamics dynamics(
            EmployeeRatingEntry current,
            EmployeeRatingEntry previous
    ) {
        if (previous == null) {
            return new EmployeeRatingDynamics(
                    null,
                    current.rank(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of()
            );
        }
        Integer rankImprovement = current.rank() == null || previous.rank() == null
                ? null : previous.rank() - current.rank();
        return new EmployeeRatingDynamics(
                previous.rank(),
                current.rank(),
                rankImprovement,
                difference(current.scores().overallScore(), previous.scores().overallScore()),
                difference(current.netRevenue(), previous.netRevenue()),
                difference(current.revenuePerHour(), previous.revenuePerHour()),
                difference(
                        current.accessorySharePercent(), previous.accessorySharePercent()
                ),
                difference(current.serviceSharePercent(), previous.serviceSharePercent()),
                difference(
                        current.additionalSharePercent(), previous.additionalSharePercent()
                ),
                attachChanges(current.attachRates(), previous.attachRates())
        );
    }

    private List<EmployeeAttachRateChange> attachChanges(
            List<EmployeeAttachRatingEntry> current,
            List<EmployeeAttachRatingEntry> previous
    ) {
        Map<String, EmployeeAttachRatingEntry> currentByCode = attachByCode(current);
        Map<String, EmployeeAttachRatingEntry> previousByCode = attachByCode(previous);
        Set<String> codes = new LinkedHashSet<>();
        codes.addAll(currentByCode.keySet());
        codes.addAll(previousByCode.keySet());
        return codes.stream().sorted().map(code -> {
            EmployeeAttachRatingEntry currentRate = currentByCode.get(code);
            EmployeeAttachRatingEntry previousRate = previousByCode.get(code);
            BigDecimal currentValue = currentRate == null ? null : currentRate.ratePercent();
            BigDecimal previousValue = previousRate == null ? null : previousRate.ratePercent();
            return new EmployeeAttachRateChange(
                    code,
                    previousValue,
                    currentValue,
                    difference(currentValue, previousValue)
            );
        }).toList();
    }

    private Map<String, EmployeeAttachRatingEntry> attachByCode(
            List<EmployeeAttachRatingEntry> entries
    ) {
        Map<String, EmployeeAttachRatingEntry> result = new HashMap<>();
        entries.forEach(entry -> result.put(entry.metricCode(), entry));
        return result;
    }

    private BigDecimal difference(BigDecimal current, BigDecimal previous) {
        return current == null || previous == null
                ? null : current.subtract(previous).setScale(SCALE, RoundingMode.HALF_UP);
    }

    private StoreKpiPeriod previous(StoreKpiPeriod current) {
        long days = ChronoUnit.DAYS.between(current.start(), current.end()) + 1;
        LocalDate end = current.start().minusDays(1);
        return new StoreKpiPeriod(end.minusDays(days - 1), end);
    }

    private Map<UUID, EmployeeRatingEntry> byEmployee(List<EmployeeRatingEntry> entries) {
        Map<UUID, EmployeeRatingEntry> result = new HashMap<>();
        entries.forEach(entry -> result.put(entry.employeeId(), entry));
        return result;
    }

    private EmployeeRatingEntry find(
            List<EmployeeRatingEntry> entries,
            UUID storeId,
            UUID employeeId
    ) {
        return entries.stream()
                .filter(entry -> entry.employeeId().equals(employeeId))
                .findFirst()
                .orElseThrow(() -> new EmployeeAssignmentNotFoundException(
                        storeId, employeeId
                ));
    }

    private Comparator<EmployeeDirectoryEntry> directoryOrder() {
        return Comparator
                .comparing(
                        (EmployeeDirectoryEntry value) -> value.current().rank(),
                        Comparator.nullsLast(Integer::compareTo)
                )
                .thenComparing(
                        value -> value.current().displayName(),
                        String.CASE_INSENSITIVE_ORDER
                );
    }
}
