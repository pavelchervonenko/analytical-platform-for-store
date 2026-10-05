package com.storeanalytics.metrics.service;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.metrics.repository.EmployeeCategoryKpiRepository;
import com.storeanalytics.metrics.repository.EmployeeKpiRepository;
import com.storeanalytics.metrics.repository.SellerCohortRepository;
import com.storeanalytics.metrics.repository.SellerDocumentRepository;
import com.storeanalytics.metrics.repository.SellerHistoricalDocumentSelection;
import com.storeanalytics.metrics.repository.SellerHistoricalDocumentSelection.Bucket;
import com.storeanalytics.metrics.repository.SellerHistoricalDocumentSelectionRepository;
import com.storeanalytics.metrics.repository.SellerMembershipHistoryRepository;
import com.storeanalytics.metrics.service.SellerHistoricalFinancialComparison.FinancialPeriod;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Opt-in temporal financial preparation; current-roster Overview and weekly publication remain separate. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class SellerHistoricalFinancialFactsService {
    private final SellerMembershipHistoryRepository history;
    private final SellerHistoricalDocumentSelectionRepository selections;
    private final SellerCohortRepository currentCohorts;
    private final EmployeeKpiRepository employees;
    private final EmployeeCategoryKpiRepository categories;
    private final SellerDocumentRepository documents;

    public SellerHistoricalFinancialFactsService(SellerMembershipHistoryRepository history,
            SellerHistoricalDocumentSelectionRepository selections, SellerCohortRepository currentCohorts,
            EmployeeKpiRepository employees, EmployeeCategoryKpiRepository categories,
            SellerDocumentRepository documents) {
        this.history = history;
        this.selections = selections;
        this.currentCohorts = currentCohorts;
        this.employees = employees;
        this.categories = categories;
        this.documents = documents;
    }

    public SellerHistoricalFinancialComparison read(UUID storeId, StoreKpiPeriod current,
            StoreKpiPeriod previous, ZoneId timezone, Instant now) {
        requireNonNull(storeId, "storeId");
        requireNonNull(current, "current");
        requireNonNull(previous, "previous");
        requireNonNull(timezone, "timezone");
        requireNonNull(now, "now");
        if (!previous.end().plusDays(1).equals(current.start())
                || current.start().getDayOfWeek() != java.time.DayOfWeek.MONDAY
                || previous.start().getDayOfWeek() != java.time.DayOfWeek.MONDAY
                || !current.start().plusDays(6).equals(current.end())
                || !previous.start().plusDays(6).equals(previous.end())) {
            throw new IllegalArgumentException("Historical comparison requires consecutive full calendar weeks");
        }
        Instant start = previous.start().atStartOfDay(timezone).toInstant();
        Instant end = current.end().plusDays(1).atStartOfDay(timezone).toInstant();
        if (now.isBefore(end)) {
            throw new SellerHistoricalFactsUnavailableException("PERIOD_NOT_CLOSED");
        }
        Instant baseline = history.authoritativeFrom(storeId)
                .orElseThrow(() -> new SellerHistoricalFactsUnavailableException("MEMBERSHIP_BASELINE_MISSING"));
        if (baseline.isAfter(start)) {
            throw new SellerHistoricalFactsUnavailableException("MEMBERSHIP_BASELINE_DOES_NOT_COVER_COMPARISON");
        }
        List<SellerHistoricalDocumentSelection> selected = selections.read(storeId, previous.start(), current.end());
        if (selected.stream().anyMatch(row -> row.bucket() == Bucket.UNKNOWN_MEMBERSHIP_HISTORY
                || row.bucket() == Bucket.UNKNOWN_EMPLOYEE_ATTRIBUTION)) {
            throw new SellerHistoricalFactsUnavailableException("DOCUMENT_MEMBERSHIP_OR_AUTHOR_UNKNOWN");
        }
        SellerCohortSnapshot cohort = new SellerCohortSnapshot(storeId, history.eligibleDuring(storeId, start, end));
        if (selected.stream().filter(row -> row.bucket() == Bucket.SELLER_ELIGIBLE)
                .anyMatch(row -> !cohort.employeeIds().contains(row.employeeId()))) {
            throw new SellerHistoricalFactsUnavailableException("DOCUMENT_TIME_OUTSIDE_MEMBERSHIP_PERIOD");
        }
        HashSet<UUID> actionIds = new HashSet<>(currentCohorts.read(storeId).employeeIds());
        actionIds.retainAll(cohort.employeeIds());
        return new SellerHistoricalFinancialComparison(period(cohort, current, selected),
                period(cohort, previous, selected), actionIds);
    }

    private FinancialPeriod period(SellerCohortSnapshot cohort, StoreKpiPeriod period,
            List<SellerHistoricalDocumentSelection> selectionsInComparison) {
        List<UUID> ids = selectionsInComparison.stream()
                .filter(row -> row.bucket() == Bucket.SELLER_ELIGIBLE)
                .filter(row -> !row.businessDate().isBefore(period.start())
                        && !row.businessDate().isAfter(period.end()))
                .map(SellerHistoricalDocumentSelection::documentId).toList();
        SellerPeriodMetrics metrics = SellerPeriodMetricCalculator.calculate(cohort, period,
                employees.aggregateSelected(cohort, period, ids), categories.aggregateSelected(cohort, period, ids));
        return new FinancialPeriod(metrics, documents.readSelected(cohort, period, ids));
    }
}
