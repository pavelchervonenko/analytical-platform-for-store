package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Action;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.AiEnhancement;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Evidence;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Factor;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Limitation;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricComparison;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.PeriodContext;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Provenance;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.QualitySummary;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.ReportState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.RevenueDecomposition;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.SalesStructureBlock;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.CoverageState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.SummaryBlock;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.TeamBlock;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.VersionSet;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

/** Seller-scoped v3 payload; activation is controlled by the dedicated seller feature flag. */
public record WeeklyReviewV3Response(
        int contractVersion,
        VersionSet versions,
        PeriodContext period,
        Provenance provenance,
        ReportState reportState,
        QualitySummary qualitySummary,
        List<SellerSourceCoverage> sourceCoverage,
        String scope,
        Membership membership,
        String sourceIdentityHash,
        SummaryBlock summary,
        List<MetricComparison> results,
        RevenueDecomposition revenueDecomposition,
        AdditionalSales additionalSales,
        List<Factor> factors,
        SalesStructureBlock salesStructure,
        TeamBlock team,
        TeamDisplay teamDisplay,
        List<EmployeeCard> employees,
        List<Action> actions,
        List<Limitation> limitations,
        List<Evidence> evidence,
        AiEnhancement aiEnhancement
) implements WeeklyReviewContract {

    public WeeklyReviewV3Response {
        require(contractVersion == 3, "contractVersion must be 3");
        requireNonNull(versions, "versions");
        requireNonNull(period, "period");
        requireNonNull(provenance, "provenance");
        requireNonNull(reportState, "reportState");
        requireNonNull(qualitySummary, "qualitySummary");
        sourceCoverage = List.copyOf(requireNonNull(sourceCoverage, "sourceCoverage"));
        require("SELLERS".equals(scope), "v3 scope must be SELLERS");
        requireNonNull(membership, "membership");
        requireHash(sourceIdentityHash, "sourceIdentityHash");
        requireNonNull(summary, "summary");
        results = List.copyOf(requireNonNull(results, "results"));
        require(results.size() == 4, "results must contain exactly four core metrics");
        requireNonNull(revenueDecomposition, "revenueDecomposition");
        requireNonNull(additionalSales, "additionalSales");
        factors = List.copyOf(requireNonNull(factors, "factors"));
        require(factors.size() <= 3, "factors must contain at most three items");
        requireNonNull(salesStructure, "salesStructure");
        requireNonNull(team, "team");
        requireNonNull(teamDisplay, "teamDisplay");
        employees = List.copyOf(requireNonNull(employees, "employees"));
        require(employees.size() <= 100, "employees must contain at most 100 items");
        require(teamDisplay.displayedCount() == employees.size(),
                "displayedCount must match employee cards");
        actions = List.copyOf(requireNonNull(actions, "actions"));
        require(actions.size() <= 3, "actions must contain at most three items");
        limitations = List.copyOf(requireNonNull(limitations, "limitations"));
        evidence = List.copyOf(requireNonNull(evidence, "evidence"));
        requireNonNull(aiEnhancement, "aiEnhancement");
        require(actions.stream().noneMatch(action -> "STORE".equals(action.scope())),
                "v3 actions must not use STORE scope");
        require(evidence.stream().noneMatch(item -> "STORE".equals(item.scope())),
                "v3 evidence must not use STORE scope");
    }

    private static void requireHash(String hash, String field) {
        require(hash != null && hash.matches("[a-f0-9]{64}"), field + " must be SHA-256 hex");
    }

    public WeeklyReviewV3Response withAiEnhancement(AiEnhancement enhancement) {
        return new WeeklyReviewV3Response(contractVersion, versions, period, provenance, reportState, qualitySummary,
                sourceCoverage, scope, membership, sourceIdentityHash, summary, results, revenueDecomposition,
                additionalSales, factors, salesStructure, team, teamDisplay, employees, actions, limitations, evidence,
                enhancement);
    }

    public record Membership(
            String basis,
            String currentCohortHash,
            String previousCohortHash,
            String actionabilityRosterHash,
            Instant actionabilityAsOf,
            int selectedSellerCount
    ) {
        public Membership {
            require("CURRENT_RANKING_AT_GENERATION".equals(basis),
                    "v3 membership basis must be current ranking");
            requireHash(currentCohortHash, "currentCohortHash");
            requireHash(previousCohortHash, "previousCohortHash");
            require(currentCohortHash.equals(previousCohortHash),
                    "stage A must use one cohort for both weeks");
            requireHash(actionabilityRosterHash, "actionabilityRosterHash");
            requireNonNull(actionabilityAsOf, "actionabilityAsOf");
            require(selectedSellerCount >= 0, "selectedSellerCount must not be negative");
        }
    }

    @Schema(name = "SellerWeeklySourceCoverage")
    public record SellerSourceCoverage(
            @Schema(allowableValues = {"SALES", "RETURNS", "ORDERS", "CLASSIFICATION", "COST",
                    "EMPLOYEE_ATTRIBUTION", "SHIFTS"}) String sourceCode,
            boolean requiredForReport,
            List<String> affectedBlockIds,
            LocalDate currentThroughDate,
            LocalDate previousThroughDate,
            CoverageState state,
            String message
    ) {
        public SellerSourceCoverage {
            require(List.of("SALES", "RETURNS", "ORDERS", "CLASSIFICATION", "COST", "EMPLOYEE_ATTRIBUTION", "SHIFTS")
                    .contains(sourceCode), "Unsupported seller source code");
            affectedBlockIds = List.copyOf(requireNonNull(affectedBlockIds, "affectedBlockIds"));
            requireNonNull(state, "state");
        }

        static SellerSourceCoverage from(WeeklyReviewResponse.SourceCoverage source) {
            return new SellerSourceCoverage(source.sourceCode().name(), source.requiredForReport(),
                    source.affectedBlockIds(), source.currentThroughDate(), source.previousThroughDate(),
                    source.state(), source.message());
        }
    }

    public record AdditionalSales(
            MetricComparison revenue,
            MetricComparison shareOfSellerRevenue,
            BigDecimal accessoryRevenue,
            BigDecimal serviceRevenue,
            BigDecimal accessoryMixShare,
            BigDecimal serviceMixShare,
            BigDecimal integrityResidual,
            boolean compositionChartSafe
    ) {
        public AdditionalSales {
            requireNonNull(revenue, "revenue");
            requireNonNull(shareOfSellerRevenue, "shareOfSellerRevenue");
            require(integrityResidual == null || integrityResidual.signum() == 0,
                    "additional sales must reconcile");
        }
    }

    public record TeamDisplay(
            int totalCount,
            int displayedCount,
            BigDecimal hiddenCurrentNetRevenue,
            BigDecimal hiddenPreviousNetRevenue,
            BigDecimal hiddenCurrentAdditionalRevenue,
            BigDecimal hiddenPreviousAdditionalRevenue
    ) {
        public TeamDisplay {
            require(totalCount >= 0 && displayedCount >= 0 && displayedCount <= totalCount,
                    "team display counts are inconsistent");
            require(displayedCount <= 100, "team display must not exceed card limit");
            requireNonNull(hiddenCurrentNetRevenue, "hiddenCurrentNetRevenue");
            requireNonNull(hiddenPreviousNetRevenue, "hiddenPreviousNetRevenue");
            requireNonNull(hiddenCurrentAdditionalRevenue, "hiddenCurrentAdditionalRevenue");
            requireNonNull(hiddenPreviousAdditionalRevenue, "hiddenPreviousAdditionalRevenue");
        }

        public int hiddenCount() {
            return totalCount - displayedCount;
        }
    }

    @Schema(name = "SellerWeeklyEmployeeCard")
    public record EmployeeCard(
            WeeklyReviewResponse.EmployeeCard card,
            boolean actionableNow
    ) {
        public EmployeeCard {
            requireNonNull(card, "card");
            require(actionableNow || card.action() == null,
                    "non-actionable employee must not receive a future action");
        }
    }
}
