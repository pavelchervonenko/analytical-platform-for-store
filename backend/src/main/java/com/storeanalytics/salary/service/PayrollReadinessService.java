package com.storeanalytics.salary.service;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.metrics.exception.StoreNotFoundException;
import com.storeanalytics.performance.model.EmployeeWorkShift;
import com.storeanalytics.performance.model.StorePerformancePlan;
import com.storeanalytics.performance.repository.EmployeeWorkShiftRepository;
import com.storeanalytics.performance.repository.StorePerformancePlanRepository;
import com.storeanalytics.salary.model.PayrollCategoryCode;
import com.storeanalytics.salary.model.PayrollPlanResult;
import com.storeanalytics.salary.model.PayrollScheme;
import com.storeanalytics.salary.repository.PayrollDailySalesAggregate;
import com.storeanalytics.salary.repository.PayrollMissingCostIssue;
import com.storeanalytics.salary.repository.PayrollReadinessRepository;
import com.storeanalytics.salary.repository.PayrollSaleSourceFact;
import com.storeanalytics.salary.repository.PayrollSalesRepository;
import com.storeanalytics.salary.repository.PayrollSchemeRepository;
import com.storeanalytics.salary.repository.PayrollUnmappedProductIssue;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.repository.StoreRepository;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PayrollReadinessService {

    private final StoreRepository storeRepository;
    private final StorePerformancePlanRepository planRepository;
    private final PayrollSchemeRepository schemeRepository;
    private final PayrollSalesRepository salesRepository;
    private final EmployeeWorkShiftRepository shiftRepository;
    private final PayrollReadinessRepository readinessRepository;
    private final PayrollComputationEngine engine = new PayrollComputationEngine();

    public PayrollReadinessService(
            StoreRepository storeRepository,
            StorePerformancePlanRepository planRepository,
            PayrollSchemeRepository schemeRepository,
            PayrollSalesRepository salesRepository,
            EmployeeWorkShiftRepository shiftRepository,
            PayrollReadinessRepository readinessRepository
    ) {
        this.storeRepository = storeRepository;
        this.planRepository = planRepository;
        this.schemeRepository = schemeRepository;
        this.salesRepository = salesRepository;
        this.shiftRepository = shiftRepository;
        this.readinessRepository = readinessRepository;
    }

    @Transactional(readOnly = true)
    public PayrollReadinessView inspect(UUID storeId, YearMonth month) {
        UUID validatedStoreId = requireNonNull(storeId, "storeId");
        YearMonth validatedMonth = requireNonNull(month, "month");
        Store store = storeRepository.findById(validatedStoreId)
                .orElseThrow(() -> new StoreNotFoundException(validatedStoreId));
        LocalDate start = validatedMonth.atDay(1);
        LocalDate end = validatedMonth.atEndOfMonth();
        Optional<StorePerformancePlan> plan = planRepository.findByStoreIdAndPlanMonth(
                validatedStoreId, start
        );
        Optional<PayrollScheme> scheme = schemeRepository
                .findFirstByEffectiveFromLessThanEqualOrderByEffectiveFromDesc(start);
        List<PayrollSaleSourceFact> saleFacts = salesRepository.sourceFacts(
                validatedStoreId, start, end
        );
        List<PayrollDailySalesAggregate> sales =
                new PayrollSaleFactAggregator().aggregate(saleFacts);
        List<EmployeeWorkShift> shifts = shiftRepository
                .findAllByStoreIdAndWorkDateBetweenOrderByWorkDateAscEmployeeFullNameAsc(
                        validatedStoreId, start, end
                ).stream().filter(EmployeeWorkShift::isActive).toList();
        PayrollComputationResult computation = plan.isPresent() && scheme.isPresent()
                ? engine.compute(new PayrollCalculationSourceData(
                        store, plan.orElseThrow(), scheme.orElseThrow(), sales, shifts
                ))
                : null;
        PayrollPlanResult planResult = computation == null
                ? null : computation.planResult();
        List<PayrollShiftIssueView> shiftIssues = computation == null
                ? List.of()
                : computation.days().stream()
                        .filter(day -> day.shifts().isEmpty())
                        .filter(day -> day.amounts().fundAmount() != null)
                        .filter(day -> day.amounts().fundAmount().signum() != 0)
                        .map(day -> new PayrollShiftIssueView(
                                day.input().workDate(), day.amounts().fundAmount()
                        ))
                        .toList();
        List<PayrollUnmappedProductView> unmapped = readinessRepository
                .unmappedProducts(validatedStoreId, start, end).stream()
                .map(this::unmappedView)
                .toList();
        List<PayrollMissingCostIssue> missingCosts = readinessRepository.missingCosts(
                validatedStoreId, start, end
        );
        int unmappedCount = sales.stream()
                .mapToInt(PayrollDailySalesAggregate::unmappedItemCount)
                .sum();
        int missingCostCount = sales.stream()
                .mapToInt(PayrollDailySalesAggregate::missingCostItemCount)
                .sum();
        boolean canCalculate = computation != null;
        boolean canApprove = canCalculate
                && unmappedCount == 0
                && missingCostCount == 0
                && shiftIssues.isEmpty();
        return new PayrollReadinessView(
                validatedStoreId,
                start,
                status(canCalculate, canApprove),
                canCalculate,
                canApprove,
                plan.isPresent(),
                scheme.isPresent(),
                planResult,
                sales.size(),
                (int) shifts.stream().map(EmployeeWorkShift::getWorkDate).distinct().count(),
                unmappedCount,
                missingCostCount,
                shiftIssues.size(),
                unmapped,
                missingCosts,
                shiftIssues
        );
    }

    private PayrollReadinessStatus status(boolean canCalculate, boolean canApprove) {
        if (!canCalculate) {
            return PayrollReadinessStatus.BLOCKED;
        }
        return canApprove
                ? PayrollReadinessStatus.READY
                : PayrollReadinessStatus.NEEDS_CORRECTION;
    }

    private PayrollUnmappedProductView unmappedView(PayrollUnmappedProductIssue issue) {
        PayrollCategoryCode suggestion = suggestedCategory(issue.productName());
        return new PayrollUnmappedProductView(
                issue.productId(),
                issue.productName(),
                issue.analyticsCategoryCode(),
                issue.firstSaleDate(),
                issue.lastSaleDate(),
                issue.saleItemCount(),
                issue.returnItemCount(),
                issue.netQuantity(),
                issue.netRevenue(),
                suggestion,
                suggestion == null ? null : "Suggestion based on product name; confirm manually"
        );
    }

    private PayrollCategoryCode suggestedCategory(String productName) {
        String name = productName.toLowerCase(Locale.ROOT);
        if ((name.contains("подпис") || name.contains("subscription"))
                && (name.contains("playstation") || name.contains(" ps"))) {
            return PayrollCategoryCode.PLAYSTATION_SUBSCRIPTION;
        }
        if (name.contains("ремонт") || name.contains("repair")) {
            return PayrollCategoryCode.PAID_REPAIR;
        }
        if (containsAny(name, "macbook", "dyson", "ps5", "playstation 5")) {
            return PayrollCategoryCode.TECH_TIER_1;
        }
        if (containsAny(
                name, "ipad", "airpods", "watch", "науш", "колонк", " dji"
        )) {
            return PayrollCategoryCode.TECH_TIER_2;
        }
        return null;
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }
}
