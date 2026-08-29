package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Direct, deterministic public contract for the v2 weekly management review. */
public record WeeklyReviewResponse(
        int contractVersion,
        VersionSet versions,
        PeriodContext period,
        Provenance provenance,
        ReportState reportState,
        QualitySummary qualitySummary,
        List<SourceCoverage> sourceCoverage,
        SummaryBlock summary,
        List<MetricComparison> results,
        RevenueDecomposition revenueDecomposition,
        List<Factor> factors,
        SalesStructureBlock salesStructure,
        TeamBlock team,
        List<EmployeeCard> employees,
        List<Action> actions,
        List<Limitation> limitations,
        List<Evidence> evidence,
        AiEnhancement aiEnhancement
) {

    public WeeklyReviewResponse {
        require(contractVersion == 2, "contractVersion must be 2");
        requireNonNull(versions, "versions");
        requireNonNull(period, "period");
        requireNonNull(provenance, "provenance");
        requireNonNull(reportState, "reportState");
        requireNonNull(qualitySummary, "qualitySummary");
        sourceCoverage = List.copyOf(requireNonNull(sourceCoverage, "sourceCoverage"));
        requireNonNull(summary, "summary");
        results = List.copyOf(requireNonNull(results, "results"));
        require(results.size() == 4, "results must contain exactly four core metrics");
        requireNonNull(revenueDecomposition, "revenueDecomposition");
        factors = List.copyOf(requireNonNull(factors, "factors"));
        require(factors.size() <= 3, "factors must contain at most three items");
        requireNonNull(salesStructure, "salesStructure");
        requireNonNull(team, "team");
        employees = List.copyOf(requireNonNull(employees, "employees"));
        require(employees.size() <= 100, "employees must contain at most 100 items");
        actions = List.copyOf(requireNonNull(actions, "actions"));
        require(actions.size() <= 3, "actions must contain at most three items");
        limitations = List.copyOf(requireNonNull(limitations, "limitations"));
        evidence = List.copyOf(requireNonNull(evidence, "evidence"));
        requireNonNull(aiEnhancement, "aiEnhancement");
    }

    public WeeklyReviewResponse withAiEnhancement(AiEnhancement replacement) {
        return new WeeklyReviewResponse(
                contractVersion,
                versions,
                period,
                provenance,
                reportState,
                qualitySummary,
                sourceCoverage,
                summary,
                results,
                revenueDecomposition,
                factors,
                salesStructure,
                team,
                employees,
                actions,
                limitations,
                evidence,
                requireNonNull(replacement, "replacement")
        );
    }

    public enum ReportState {
        PREPARING,
        READY,
        PARTIAL,
        BLOCKED
    }

    public enum BlockState {
        READY,
        LIMITED,
        INSUFFICIENT,
        NOT_APPLICABLE
    }

    public enum MetricState {
        READY,
        LIMITED,
        UNAVAILABLE,
        NOT_APPLICABLE
    }

    public enum Sufficiency {
        SUFFICIENT,
        LIMITED,
        INSUFFICIENT,
        NOT_EVALUATED
    }

    public enum Materiality {
        MATERIAL,
        NOT_MATERIAL,
        NOT_EVALUATED
    }

    public enum Unit {
        RUB,
        PERCENT,
        PER_100,
        COUNT,
        HOURS,
        STATUS
    }

    public enum Direction {
        UP,
        DOWN,
        FLAT,
        UNKNOWN
    }

    public enum Effect {
        POSITIVE,
        NEGATIVE,
        NEUTRAL,
        UNKNOWN
    }

    public enum ComparisonKind {
        PERCENT_AVAILABLE,
        NO_BASE,
        NON_POSITIVE_BASE,
        UNAVAILABLE
    }

    public enum GeneratedBy {
        DETERMINISTIC,
        AI_ENHANCED
    }

    public record VersionSet(
            String metricsPolicy,
            String snapshotPolicy,
            String qualityPolicy
    ) {

        public VersionSet {
            requireText(metricsPolicy, "metricsPolicy");
            requireText(snapshotPolicy, "snapshotPolicy");
            requireText(qualityPolicy, "qualityPolicy");
        }
    }

    public record DateRange(LocalDate start, LocalDate end) {

        public DateRange {
            requireNonNull(start, "start");
            requireNonNull(end, "end");
            require(!end.isBefore(start), "end must not precede start");
        }
    }

    public record PeriodContext(
            String timezone,
            DateRange current,
            DateRange previous,
            String currentLabel,
            String previousLabel
    ) {

        public PeriodContext {
            requireText(timezone, "timezone");
            requireNonNull(current, "current");
            requireNonNull(previous, "previous");
            requireText(currentLabel, "currentLabel");
            requireText(previousLabel, "previousLabel");
            require(current.start().minusDays(1).equals(previous.end()),
                    "comparison weeks must be adjacent");
            require(current.end().minusDays(6).equals(current.start()),
                    "current period must contain seven days");
            require(previous.end().minusDays(6).equals(previous.start()),
                    "previous period must contain seven days");
        }
    }

    public record Provenance(
            String snapshotPublicId,
            int revision,
            Instant calculatedAt,
            Instant sourceDataUpdatedAt,
            boolean revisionChanged,
            Instant previousRevisionPublishedAt
    ) {

        public Provenance {
            requireText(snapshotPublicId, "snapshotPublicId");
            require(revision > 0, "revision must be positive");
            requireNonNull(calculatedAt, "calculatedAt");
        }
    }

    public record QualitySummary(
            int blockingCount,
            int warningCount,
            int affectedBlockCount,
            String message
    ) {

        public QualitySummary {
            require(blockingCount >= 0, "blockingCount must not be negative");
            require(warningCount >= 0, "warningCount must not be negative");
            require(affectedBlockCount >= 0, "affectedBlockCount must not be negative");
            requireText(message, "message");
        }
    }

    public enum SourceCode {
        SALES,
        RETURNS,
        CLASSIFICATION,
        COST,
        EMPLOYEE_ATTRIBUTION,
        SHIFTS
    }

    public enum CoverageState {
        COMPLETE,
        PARTIAL,
        MISSING,
        NOT_REQUIRED
    }

    public record SourceCoverage(
            SourceCode sourceCode,
            boolean requiredForReport,
            List<String> affectedBlockIds,
            LocalDate currentThroughDate,
            LocalDate previousThroughDate,
            CoverageState state,
            String message
    ) {

        public SourceCoverage {
            requireNonNull(sourceCode, "sourceCode");
            affectedBlockIds = List.copyOf(requireNonNull(
                    affectedBlockIds, "affectedBlockIds"
            ));
            requireNonNull(state, "state");
        }
    }

    public record SummaryBlock(
            String blockId,
            BlockState state,
            NarrativeItem outcome,
            NarrativeItem positive,
            NarrativeItem risk,
            GeneratedBy generatedBy
    ) {

        public SummaryBlock {
            requireText(blockId, "blockId");
            requireNonNull(state, "state");
            requireNonNull(generatedBy, "generatedBy");
        }
    }

    public record NarrativeItem(
            String itemId,
            String text,
            Effect effect,
            List<String> evidenceRefs
    ) {

        public NarrativeItem {
            requireText(itemId, "itemId");
            requireText(text, "text");
            requireNonNull(effect, "effect");
            evidenceRefs = nonEmptyReferences(evidenceRefs, "evidenceRefs");
        }
    }

    public record Sample(
            BigDecimal numerator,
            BigDecimal denominator,
            String numeratorLabel,
            String denominatorLabel
    ) {
    }

    public record MetricComparison(
            String metricId,
            String code,
            String label,
            Unit unit,
            BigDecimal current,
            BigDecimal previous,
            BigDecimal absoluteDelta,
            BigDecimal changePercent,
            ComparisonKind comparisonKind,
            Direction direction,
            Effect effect,
            MetricState metricState,
            Sufficiency sufficiency,
            Materiality materiality,
            Sample currentSample,
            Sample previousSample,
            List<String> evidenceRefs
    ) {

        public MetricComparison {
            requireText(metricId, "metricId");
            requireText(code, "code");
            requireText(label, "label");
            requireNonNull(unit, "unit");
            requireNonNull(comparisonKind, "comparisonKind");
            requireNonNull(direction, "direction");
            requireNonNull(effect, "effect");
            requireNonNull(metricState, "metricState");
            requireNonNull(sufficiency, "sufficiency");
            requireNonNull(materiality, "materiality");
            evidenceRefs = nonEmptyReferences(evidenceRefs, "evidenceRefs");
            require(current != null && previous != null || absoluteDelta == null,
                    "absoluteDelta requires both values");
            require(materiality != Materiality.MATERIAL
                            || sufficiency == Sufficiency.SUFFICIENT,
                    "material metric must be sufficient");
        }
    }

    public record RevenueDecomposition(
            MetricComparison salesRevenue,
            MetricComparison returnRevenue,
            MetricComparison netRevenue,
            MetricComparison saleDocumentCount,
            MetricComparison returnDocumentCount,
            boolean identityValid
    ) {

        public RevenueDecomposition {
            requireNonNull(salesRevenue, "salesRevenue");
            requireNonNull(returnRevenue, "returnRevenue");
            requireNonNull(netRevenue, "netRevenue");
            requireNonNull(saleDocumentCount, "saleDocumentCount");
            requireNonNull(returnDocumentCount, "returnDocumentCount");
            require(identityValid, "revenue identity must be valid");
        }
    }

    public record Factor(
            String factorId,
            String kind,
            String title,
            String detail,
            MetricComparison comparison,
            BigDecimal contributionAmount,
            Effect effect,
            List<String> evidenceRefs
    ) {

        public Factor {
            requireText(factorId, "factorId");
            requireText(kind, "kind");
            requireText(title, "title");
            requireText(detail, "detail");
            requireNonNull(comparison, "comparison");
            require(effect == Effect.POSITIVE || effect == Effect.NEGATIVE,
                    "factor effect must be positive or negative");
            evidenceRefs = nonEmptyReferences(evidenceRefs, "evidenceRefs");
        }
    }

    public record SalesStructureBlock(
            String blockId,
            BlockState state,
            StructureNode root,
            List<AttachMetric> attachMetrics,
            List<String> limitations
    ) {

        public SalesStructureBlock {
            requireText(blockId, "blockId");
            requireNonNull(state, "state");
            requireNonNull(root, "root");
            attachMetrics = List.copyOf(requireNonNull(attachMetrics, "attachMetrics"));
            limitations = List.copyOf(requireNonNull(limitations, "limitations"));
        }
    }

    public record StructureNode(
            String nodeId,
            String code,
            String label,
            boolean subtotal,
            boolean childrenIncludedInValue,
            MetricComparison comparison,
            MetricComparison shareComparison,
            List<StructureNode> children
    ) {

        public StructureNode {
            requireText(nodeId, "nodeId");
            requireText(code, "code");
            requireText(label, "label");
            requireNonNull(comparison, "comparison");
            requireNonNull(shareComparison, "shareComparison");
            children = List.copyOf(requireNonNull(children, "children"));
        }
    }

    public record AttachMetric(
            String metricId,
            String code,
            String label,
            MetricComparison comparison
    ) {

        public AttachMetric {
            requireText(metricId, "metricId");
            requireText(code, "code");
            requireText(label, "label");
            requireNonNull(comparison, "comparison");
        }
    }

    public record TeamBlock(
            String blockId,
            BlockState state,
            RosterSummary roster,
            List<Observation> observations,
            int attentionEmployeeCount,
            BenchmarkPolicy benchmarkPolicy,
            List<String> limitations
    ) {

        public TeamBlock {
            requireText(blockId, "blockId");
            requireNonNull(state, "state");
            requireNonNull(roster, "roster");
            observations = List.copyOf(requireNonNull(observations, "observations"));
            require(observations.size() <= 2, "team observations must contain at most two items");
            require(attentionEmployeeCount >= 0,
                    "attentionEmployeeCount must not be negative");
            requireNonNull(benchmarkPolicy, "benchmarkPolicy");
            limitations = List.copyOf(requireNonNull(limitations, "limitations"));
        }
    }

    public record RosterSummary(
            int activeAssignedWithActivity,
            int participatesInBenchmark,
            int sufficientByAnyMetric,
            int limitedOrInsufficient,
            int excludedFromBenchmark
    ) {
    }

    public record BenchmarkPolicy(
            String method,
            int minimumEligibleCount,
            String label
    ) {

        public BenchmarkPolicy {
            require("MEDIAN".equals(method), "benchmark method must be MEDIAN");
            require(minimumEligibleCount == 3,
                    "minimumEligibleCount must be 3");
            requireText(label, "label");
        }
    }

    public record Observation(
            String observationId,
            String title,
            String detail,
            Effect effect,
            List<String> evidenceRefs
    ) {

        public Observation {
            requireText(observationId, "observationId");
            requireText(title, "title");
            requireText(detail, "detail");
            requireNonNull(effect, "effect");
            evidenceRefs = nonEmptyReferences(evidenceRefs, "evidenceRefs");
        }
    }

    public record EmployeeCard(
            String employeePublicId,
            String displayName,
            boolean participatesInBenchmark,
            String sortGroup,
            EmployeeMetricSet metrics,
            List<Observation> ownDynamics,
            PeerComparison peerComparison,
            Observation strength,
            Observation attention,
            Action action,
            List<String> limitations
    ) {

        public EmployeeCard {
            requireText(employeePublicId, "employeePublicId");
            requireText(displayName, "displayName");
            requireText(sortGroup, "sortGroup");
            requireNonNull(metrics, "metrics");
            ownDynamics = List.copyOf(requireNonNull(ownDynamics, "ownDynamics"));
            require(ownDynamics.size() <= 2,
                    "ownDynamics must contain at most two items");
            limitations = List.copyOf(requireNonNull(limitations, "limitations"));
        }
    }

    public record EmployeeMetricSet(
            MetricComparison completedSales,
            MetricComparison netRevenue,
            MetricComparison additionalRevenue,
            MetricComparison additionalShare,
            MetricComparison shiftCount,
            MetricComparison workedHours,
            MetricComparison revenuePerHour,
            List<AttachMetric> attachMetrics
    ) {

        public EmployeeMetricSet {
            requireNonNull(completedSales, "completedSales");
            requireNonNull(netRevenue, "netRevenue");
            requireNonNull(additionalRevenue, "additionalRevenue");
            requireNonNull(additionalShare, "additionalShare");
            requireNonNull(shiftCount, "shiftCount");
            requireNonNull(workedHours, "workedHours");
            requireNonNull(revenuePerHour, "revenuePerHour");
            attachMetrics = List.copyOf(requireNonNull(attachMetrics, "attachMetrics"));
            require(attachMetrics.size() <= 2,
                    "employee attachMetrics must contain at most two items");
        }
    }

    public record PeerComparison(
            String metricCode,
            BigDecimal employeeValue,
            BigDecimal benchmarkValue,
            String benchmarkMethod,
            int eligibleCount,
            BigDecimal absoluteDelta,
            BigDecimal changePercent,
            Effect effect,
            List<String> evidenceRefs
    ) {

        public PeerComparison {
            requireText(metricCode, "metricCode");
            requireNonNull(employeeValue, "employeeValue");
            requireNonNull(benchmarkValue, "benchmarkValue");
            require("MEDIAN".equals(benchmarkMethod),
                    "benchmarkMethod must be MEDIAN");
            require(eligibleCount >= 3, "peer comparison requires three employees");
            requireNonNull(absoluteDelta, "absoluteDelta");
            requireNonNull(effect, "effect");
            evidenceRefs = nonEmptyReferences(evidenceRefs, "evidenceRefs");
        }
    }

    public record Action(
            String actionId,
            String priority,
            String actionType,
            String scope,
            String employeePublicId,
            String title,
            String metricCode,
            ActionTarget target,
            String check,
            String horizon,
            GeneratedBy generatedBy,
            List<String> evidenceRefs
    ) {

        public Action {
            requireText(actionId, "actionId");
            requireText(priority, "priority");
            requireText(actionType, "actionType");
            requireText(scope, "scope");
            requireText(title, "title");
            requireText(metricCode, "metricCode");
            requireNonNull(target, "target");
            requireText(check, "check");
            require("NEXT_FULL_WEEK".equals(horizon),
                    "action horizon must be NEXT_FULL_WEEK");
            requireNonNull(generatedBy, "generatedBy");
            evidenceRefs = nonEmptyReferences(evidenceRefs, "evidenceRefs");
        }
    }

    public record ActionTarget(
            String operator,
            BigDecimal value,
            Unit unit
    ) {

        public ActionTarget {
            requireText(operator, "operator");
            requireNonNull(value, "value");
            requireNonNull(unit, "unit");
        }
    }

    public record Limitation(
            String limitationId,
            String code,
            String severity,
            String scope,
            String employeePublicId,
            List<String> affectedBlockIds,
            List<String> affectedMetricCodes,
            DateRange period,
            int affectedCount,
            String summary,
            String resolution,
            List<String> evidenceRefs
    ) {

        public Limitation {
            requireText(limitationId, "limitationId");
            requireText(code, "code");
            requireText(severity, "severity");
            requireText(scope, "scope");
            affectedBlockIds = nonEmptyReferences(
                    affectedBlockIds, "affectedBlockIds"
            );
            affectedMetricCodes = List.copyOf(requireNonNull(
                    affectedMetricCodes, "affectedMetricCodes"
            ));
            requireNonNull(period, "period");
            require(affectedCount > 0, "affectedCount must be positive");
            requireText(summary, "summary");
            evidenceRefs = List.copyOf(requireNonNull(evidenceRefs, "evidenceRefs"));
        }
    }

    public record Evidence(
            String evidenceRef,
            String scope,
            String employeePublicId,
            String metricCode,
            String label,
            Unit unit,
            DateRange currentPeriod,
            DateRange previousPeriod,
            Object currentValue,
            Object previousValue,
            BigDecimal currentNumerator,
            BigDecimal currentDenominator,
            BigDecimal previousNumerator,
            BigDecimal previousDenominator,
            String formulaVersion,
            Sufficiency sufficiency,
            Materiality materiality,
            boolean available
    ) {

        public Evidence {
            requireText(evidenceRef, "evidenceRef");
            requireText(scope, "scope");
            requireText(metricCode, "metricCode");
            requireText(label, "label");
            requireNonNull(unit, "unit");
            requireNonNull(currentPeriod, "currentPeriod");
            requireText(formulaVersion, "formulaVersion");
            requireNonNull(sufficiency, "sufficiency");
            requireNonNull(materiality, "materiality");
        }
    }

    public enum AiState {
        PREPARING,
        READY,
        DELAYED,
        UNAVAILABLE,
        DISABLED,
        NOT_APPLICABLE
    }

    public record AiEnhancement(
            AiState state,
            String promptVersion,
            Integer contentSchemaVersion,
            Instant publishedAt
    ) {

        public AiEnhancement {
            requireNonNull(state, "state");
        }
    }

    private static List<String> nonEmptyReferences(
            List<String> values,
            String fieldName
    ) {
        List<String> result = List.copyOf(requireNonNull(values, fieldName));
        require(!result.isEmpty(), fieldName + " must not be empty");
        result.forEach(value -> requireText(value, fieldName));
        return result;
    }
}
