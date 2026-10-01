package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.metrics.repository.EmployeeCategoryKpiAggregate;
import com.storeanalytics.metrics.repository.EmployeeKpiAggregate;
import com.storeanalytics.metrics.repository.SellerDocumentAggregate;
import com.storeanalytics.metrics.service.CategoryKpiGroup;
import com.storeanalytics.metrics.service.SellerPeriodComparisonFacts;
import com.storeanalytics.metrics.service.SellerPeriodFacts;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Full-cohort financial facts. Workload and display-card policy are separate dependencies. */
final class SellerWeeklyTeamFactsProjector {

    private static final int DISPLAY_LIMIT = 100;

    TeamFinancialFacts project(SellerPeriodComparisonFacts comparison) {
        SellerPeriodComparisonFacts facts = requireNonNull(comparison, "comparison");
        SellerPeriodFacts current = facts.current();
        SellerPeriodFacts previous = facts.previous();
        List<UUID> ids = current.metrics().cohort().employeeIds();
        Map<UUID, EmployeeKpiAggregate> currentRows = byId(current.metrics().employees(),
                EmployeeKpiAggregate::employeeId);
        Map<UUID, EmployeeKpiAggregate> previousRows = byId(previous.metrics().employees(),
                EmployeeKpiAggregate::employeeId);
        Map<UUID, SellerDocumentAggregate> currentDocuments = byId(current.documents(),
                SellerDocumentAggregate::employeeId);
        Map<UUID, SellerDocumentAggregate> previousDocuments = byId(previous.documents(),
                SellerDocumentAggregate::employeeId);
        Map<UUID, BigDecimal> currentAdditional = additionalByEmployee(current);
        Map<UUID, BigDecimal> previousAdditional = additionalByEmployee(previous);
        List<EmployeeContribution> employees = ids.stream().map(id -> {
            EmployeeKpiAggregate currentRow = requiredRow(currentRows, id);
            EmployeeKpiAggregate previousRow = requiredRow(previousRows, id);
            return new EmployeeContribution(id, currentRow.displayName(),
                    period(currentRow, currentDocuments.get(id), currentAdditional.getOrDefault(id,
                            BigDecimal.ZERO)),
                    period(previousRow, previousDocuments.get(id), previousAdditional.getOrDefault(id,
                            BigDecimal.ZERO)));
        }).toList();
        TeamFinancialFacts team = new TeamFinancialFacts(employees,
                current.metrics().totals().netRevenue(), previous.metrics().totals().netRevenue(),
                groupAdditional(current), groupAdditional(previous));
        reconcile(team);
        return team;
    }

    private <T> Map<UUID, T> byId(List<T> rows, Function<T, UUID> id) {
        return requireNonNull(rows, "rows").stream().collect(Collectors.toMap(
                id, Function.identity(), (first, second) -> {
                    throw new IllegalStateException("Duplicate seller fact for employee");
                }));
    }

    private EmployeeKpiAggregate requiredRow(Map<UUID, EmployeeKpiAggregate> rows, UUID id) {
        EmployeeKpiAggregate row = rows.get(id);
        if (row == null) {
            throw new IllegalStateException("Selected seller is missing from weekly employee facts");
        }
        return row;
    }

    private Map<UUID, BigDecimal> additionalByEmployee(SellerPeriodFacts facts) {
        Map<UUID, BigDecimal> amounts = new HashMap<>();
        for (EmployeeCategoryKpiAggregate row : facts.metrics().employeeCategories()) {
            if (row.countsAsAdditionalRevenue()) {
                amounts.merge(row.employeeId(), row.netRevenue(), BigDecimal::add);
            }
        }
        return amounts;
    }

    private BigDecimal groupAdditional(SellerPeriodFacts facts) {
        return facts.metrics().categories().groups().stream()
                .filter(group -> "ADDITIONAL_REVENUE".equals(group.groupCode()))
                .map(CategoryKpiGroup::metrics)
                .map(metrics -> metrics.netRevenue())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Seller additional group is missing"));
    }

    private PeriodEmployeeFacts period(
            EmployeeKpiAggregate employee,
            SellerDocumentAggregate document,
            BigDecimal additional
    ) {
        BigDecimal sales = document == null ? BigDecimal.ZERO : document.salesRevenue();
        BigDecimal returns = document == null ? BigDecimal.ZERO : document.returnRevenue();
        if (employee.netRevenue().compareTo(sales.subtract(returns)) != 0) {
            throw new IllegalStateException("Seller employee/document revenue reconciliation failed");
        }
        return new PeriodEmployeeFacts(employee.netRevenue(), sales, returns, additional,
                document == null ? 0 : document.saleDocumentCount(),
                document == null ? 0 : document.returnDocumentCount(),
                document == null ? 0 : document.completedSaleCount(),
                employee.includedItemCount());
    }

    private void reconcile(TeamFinancialFacts team) {
        require(sum(team.employees(), employee -> employee.current().netRevenue())
                        .compareTo(team.currentNetRevenue()) == 0,
                "Team current revenue must include every selected seller");
        require(sum(team.employees(), employee -> employee.previous().netRevenue())
                        .compareTo(team.previousNetRevenue()) == 0,
                "Team previous revenue must include every selected seller");
        require(sum(team.employees(), employee -> employee.current().additionalRevenue())
                        .compareTo(team.currentAdditionalRevenue()) == 0,
                "Team current additional must include every selected seller");
        require(sum(team.employees(), employee -> employee.previous().additionalRevenue())
                        .compareTo(team.previousAdditionalRevenue()) == 0,
                "Team previous additional must include every selected seller");
    }

    private BigDecimal sum(
            List<EmployeeContribution> employees,
            Function<EmployeeContribution, BigDecimal> value
    ) {
        return employees.stream().map(value).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    record PeriodEmployeeFacts(
            BigDecimal netRevenue,
            BigDecimal salesRevenue,
            BigDecimal returnRevenue,
            BigDecimal additionalRevenue,
            long saleDocumentCount,
            long returnDocumentCount,
            long completedSaleCount,
            long includedItemCount
    ) {
        boolean hasFinancialActivity() {
            return saleDocumentCount > 0 || returnDocumentCount > 0 || includedItemCount > 0
                    || netRevenue.signum() != 0 || additionalRevenue.signum() != 0;
        }
    }

    record EmployeeContribution(
            UUID employeeId,
            String displayName,
            PeriodEmployeeFacts current,
            PeriodEmployeeFacts previous
    ) {
        boolean hasFinancialActivity() {
            return current.hasFinancialActivity() || previous.hasFinancialActivity();
        }
    }

    record TeamFinancialFacts(
            List<EmployeeContribution> employees,
            BigDecimal currentNetRevenue,
            BigDecimal previousNetRevenue,
            BigDecimal currentAdditionalRevenue,
            BigDecimal previousAdditionalRevenue
    ) {
        TeamFinancialFacts {
            employees = List.copyOf(requireNonNull(employees, "employees"));
        }

        /** The caller orders candidate cards; all active financial contributors must remain accounted for. */
        DisplayWindow displayWindow(List<UUID> orderedCandidates) {
            List<UUID> ordered = List.copyOf(requireNonNull(orderedCandidates, "orderedCandidates"));
            Map<UUID, EmployeeContribution> byId = employees.stream().collect(Collectors.toMap(
                    EmployeeContribution::employeeId, Function.identity()));
            Set<UUID> unique = new HashSet<>(ordered);
            require(unique.size() == ordered.size() && byId.keySet().containsAll(unique),
                    "Display order must contain unique selected seller IDs");
            require(unique.containsAll(employees.stream().filter(EmployeeContribution::hasFinancialActivity)
                    .map(EmployeeContribution::employeeId).toList()),
                    "Display order must include every financially active seller");
            List<EmployeeContribution> candidates = ordered.stream().map(byId::get).toList();
            int displayed = Math.min(DISPLAY_LIMIT, candidates.size());
            List<EmployeeContribution> visible = candidates.subList(0, displayed);
            List<EmployeeContribution> hidden = candidates.subList(displayed, candidates.size());
            return new DisplayWindow(visible, candidates.size(), hidden.size(),
                    hidden.stream().map(employee -> employee.current().netRevenue())
                            .reduce(BigDecimal.ZERO, BigDecimal::add),
                    hidden.stream().map(employee -> employee.previous().netRevenue())
                            .reduce(BigDecimal.ZERO, BigDecimal::add),
                    hidden.stream().map(employee -> employee.current().additionalRevenue())
                            .reduce(BigDecimal.ZERO, BigDecimal::add),
                    hidden.stream().map(employee -> employee.previous().additionalRevenue())
                            .reduce(BigDecimal.ZERO, BigDecimal::add));
        }

        List<UUID> financiallyActiveIds() {
            return employees.stream().filter(EmployeeContribution::hasFinancialActivity)
                    .map(EmployeeContribution::employeeId).toList();
        }
    }

    record DisplayWindow(
            List<EmployeeContribution> visible,
            int totalCount,
            int hiddenCount,
            BigDecimal hiddenCurrentNetRevenue,
            BigDecimal hiddenPreviousNetRevenue,
            BigDecimal hiddenCurrentAdditionalRevenue,
            BigDecimal hiddenPreviousAdditionalRevenue
    ) {
        DisplayWindow {
            visible = List.copyOf(visible);
        }

        int displayedCount() {
            return visible.size();
        }
    }
}
