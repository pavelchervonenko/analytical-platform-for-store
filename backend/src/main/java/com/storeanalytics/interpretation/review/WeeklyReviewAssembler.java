package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.AiState.DISABLED;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.BlockState.READY;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.Effect.NEGATIVE;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.Effect.POSITIVE;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.GeneratedBy.DETERMINISTIC;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.Materiality.MATERIAL;

import com.storeanalytics.interpretation.review.WeeklyReviewCoreProjector.Projection;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Action;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.ActionTarget;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.AiEnhancement;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.EmployeeCard;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Evidence;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Factor;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Materiality;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricComparison;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Observation;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Provenance;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.QualitySummary;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.ReportState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.RevenueDecomposition;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.SalesStructureBlock;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.StructureNode;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Sufficiency;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.SummaryBlock;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.TeamBlock;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Unit;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Creates the direct UI model without requiring an AI provider. */
public final class WeeklyReviewAssembler {

    private static final int MAX_FACTORS = 3;
    private static final int MAX_ACTIONS = 3;

    private final WeeklyReviewCoreProjector coreProjector;
    private final WeeklyReviewStructureProjector structureProjector;
    private final WeeklyReviewTeamEmployeeProjector teamEmployeeProjector;
    private final WeeklyReviewQualityPolicyV1 qualityPolicy;
    private final WeeklyReviewSummaryPresenter summaryPresenter;

    public WeeklyReviewAssembler(WeeklyReviewPolicyV1 policy) {
        WeeklyReviewPolicyV1 validated = requireNonNull(policy, "policy");
        this.coreProjector = new WeeklyReviewCoreProjector();
        this.structureProjector = new WeeklyReviewStructureProjector(validated);
        this.teamEmployeeProjector = new WeeklyReviewTeamEmployeeProjector(validated);
        this.qualityPolicy = new WeeklyReviewQualityPolicyV1();
        this.summaryPresenter = new WeeklyReviewSummaryPresenter();
    }

    public WeeklyReviewResponse assemble(WeeklyReviewFacts facts, Provenance provenance) {
        WeeklyReviewFacts source = requireNonNull(facts, "facts");
        Provenance snapshot = requireNonNull(provenance, "provenance");
        WeeklyReviewQualityPolicyV1.Decision quality = qualityPolicy.decide(
                source.sourceDataStatus(),
                source.current().store(),
                source.previous().store(),
                source.period().current(),
                source.period().previous(),
                source.current().unattributedReturnDocumentCount(),
                source.previous().unattributedReturnDocumentCount()
        );
        boolean sourceAnalyticsBlocked = quality.reportState() == ReportState.BLOCKED;
        Projection core = coreProjector.project(
                source.current().store(),
                source.previous().store(),
                source.current().revenue(),
                source.previous().revenue(),
                sourceAnalyticsBlocked
        );
        SalesStructureBlock structure = sourceAnalyticsBlocked
                ? structureProjector.unavailable()
                : structureProjector.project(
                        source.current().store(),
                        source.previous().store(),
                        source.current().categories(),
                        source.previous().categories(),
                        source.current().attachRates(),
                        source.previous().attachRates()
                );
        WeeklyReviewTeamEmployeeProjector.Projection people = sourceAnalyticsBlocked
                ? teamEmployeeProjector.unavailable()
                : teamEmployeeProjector.project(
                        source.current().employeeFacts(),
                        source.previous().employeeFacts(),
                        source.current().employeeSalesSamples(),
                        source.previous().employeeSalesSamples(),
                        source.current().unattributedReturnDocumentCount(),
                        source.previous().unattributedReturnDocumentCount(),
                        attachLabels(source)
                );
        ReportState reportState = reportState(quality, structure, people.team());
        QualitySummary qualitySummary = qualitySummary(
                reportState,
                quality,
                structure,
                people.team()
        );
        List<Factor> factors = reportState == ReportState.BLOCKED
                ? List.of()
                : factors(core.revenueDecomposition(), structure);
        List<Action> actions = actions(factors);
        SummaryBlock summary = summaryPresenter.present(reportState, core.results(), factors);
        List<Evidence> evidence = evidence(
                source,
                core,
                structure,
                people.team(),
                people.employees(),
                sourceAnalyticsBlocked
        );
        return new WeeklyReviewResponse(
                2,
                WeeklyReviewPolicyV1.VERSIONS,
                source.period(),
                snapshot,
                reportState,
                qualitySummary,
                quality.sourceCoverage(),
                summary,
                core.results(),
                core.revenueDecomposition(),
                factors,
                structure,
                people.team(),
                people.employees(),
                actions,
                quality.limitations(),
                evidence,
                new AiEnhancement(DISABLED, null, null, null)
        );
    }

    private ReportState reportState(
            WeeklyReviewQualityPolicyV1.Decision quality,
            SalesStructureBlock structure,
            TeamBlock team
    ) {
        if (quality.reportState() == ReportState.BLOCKED) {
            return ReportState.BLOCKED;
        }
        if (quality.reportState() == ReportState.PARTIAL
                || structure.state() != READY
                || team.state() != READY) {
            return ReportState.PARTIAL;
        }
        return ReportState.READY;
    }

    private QualitySummary qualitySummary(
            ReportState reportState,
            WeeklyReviewQualityPolicyV1.Decision quality,
            SalesStructureBlock structure,
            TeamBlock team
    ) {
        if (reportState != ReportState.PARTIAL) {
            return quality.qualitySummary();
        }
        Set<String> affectedBlocks = quality.limitations().stream()
                .flatMap(limitation -> limitation.affectedBlockIds().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        int localWarningCount = 0;
        if (structure.state() != READY && affectedBlocks.add(structure.blockId())) {
            localWarningCount++;
        }
        if (team.state() != READY && affectedBlocks.add(team.blockId())) {
            localWarningCount++;
        }
        int warningCount = quality.qualitySummary().warningCount() + localWarningCount;
        return new QualitySummary(
                quality.qualitySummary().blockingCount(),
                warningCount,
                affectedBlocks.size(),
                "Надёжные показатели сохранены; ограничений: " + warningCount
        );
    }

    private List<Factor> factors(
            RevenueDecomposition revenue,
            SalesStructureBlock structure
    ) {
        List<FactorCandidate> candidates = new ArrayList<>();
        addCandidate(candidates, 0, "RETURN_CHANGE", revenue.returnRevenue(), null);
        structure.root().children().stream()
                .filter(node -> "ADDITIONAL_REVENUE".equals(node.code())
                        || "DEVICES".equals(node.code()))
                .forEach(node -> addCandidate(
                        candidates, 1, "STRUCTURE_CHANGE", node.comparison(), null
                ));
        structure.attachMetrics().forEach(attach -> addCandidate(
                candidates, 1, "ATTACH_CHANGE", attach.comparison(), null
        ));
        return candidates.stream()
                .filter(candidate -> candidate.comparison().materiality() == MATERIAL)
                .sorted(Comparator
                        .comparingInt(FactorCandidate::priority)
                        .thenComparing(candidate ->
                                candidate.comparison().effect() == NEGATIVE ? 0 : 1)
                        .thenComparing(candidate -> candidate.comparison().code()))
                .limit(MAX_FACTORS)
                .map(this::factor)
                .toList();
    }

    private void addCandidate(
            List<FactorCandidate> target,
            int priority,
            String kind,
            MetricComparison comparison,
            BigDecimal contribution
    ) {
        if (comparison.metricState() == WeeklyReviewResponse.MetricState.READY
                && comparison.sufficiency() == Sufficiency.SUFFICIENT
                && (comparison.effect() == POSITIVE || comparison.effect() == NEGATIVE)) {
            BigDecimal actualContribution = "RETURN_CHANGE".equals(kind)
                    && comparison.absoluteDelta() != null
                    ? comparison.absoluteDelta().negate()
                    : contribution;
            target.add(new FactorCandidate(
                    priority,
                    kind,
                    comparison,
                    actualContribution
            ));
        }
    }

    private Factor factor(FactorCandidate candidate) {
        MetricComparison comparison = candidate.comparison();
        String detail = comparison.label() + ": "
                + format(comparison.current(), comparison.unit())
                + " против " + format(comparison.previous(), comparison.unit())
                + comparisonText(comparison);
        return new Factor(
                "factor:" + comparison.code().toLowerCase(Locale.ROOT),
                candidate.kind(),
                factorTitle(candidate),
                detail,
                comparison,
                candidate.contributionAmount(),
                comparison.effect(),
                comparison.evidenceRefs()
        );
    }

    private String factorTitle(FactorCandidate candidate) {
        MetricComparison comparison = candidate.comparison();
        boolean increased = comparison.direction() == WeeklyReviewResponse.Direction.UP;
        return switch (candidate.kind()) {
            case "RETURN_CHANGE" -> "Возвраты "
                    + (increased ? "выросли" : "снизились");
            case "STRUCTURE_CHANGE" -> structureFactorTitle(
                    comparison.label(), increased
            );
            case "ATTACH_CHANGE" -> "Допродажи «" + comparison.label() + "» "
                    + (increased ? "выросли" : "снизились");
            default -> "Показатель «" + comparison.label() + "» "
                    + (increased ? "вырос" : "снизился");
        };
    }

    private String structureFactorTitle(String label, boolean increased) {
        String subject = "Дополнительная выручка".equals(label)
                ? label
                : "Выручка направления «" + label + "»";
        return subject + " " + (increased ? "выросла" : "снизилась");
    }

    private List<Action> actions(List<Factor> factors) {
        return factors.stream()
                .filter(factor -> factor.effect() == NEGATIVE)
                .filter(factor -> factor.comparison().previous() != null)
                .limit(MAX_ACTIONS)
                .map(this::action)
                .toList();
    }

    private Action action(Factor factor) {
        MetricComparison metric = factor.comparison();
        boolean lowerIsBetter = "RETURN_REVENUE".equals(metric.code());
        String operator = lowerIsBetter ? "AT_MOST" : "AT_LEAST";
        return new Action(
                "action:restore:" + metric.code().toLowerCase(Locale.ROOT),
                "HIGH",
                "RESTORE_METRIC",
                "STORE",
                null,
                actionTitle(metric),
                metric.code(),
                new ActionTarget(operator, metric.previous(), metric.unit()),
                "Сравнить следующую полную неделю с "
                        + format(metric.previous(), metric.unit()),
                "NEXT_FULL_WEEK",
                DETERMINISTIC,
                factor.evidenceRefs()
        );
    }

    private String actionTitle(MetricComparison metric) {
        if ("RETURN_REVENUE".equals(metric.code())
                && metric.direction() == WeeklyReviewResponse.Direction.UP) {
            return "Проверить чеки и причины возвратов";
        }
        return metric.direction() == WeeklyReviewResponse.Direction.DOWN
                ? "Разобрать снижение «" + metric.label() + "»"
                : "Проверить изменение «" + metric.label() + "»";
    }

    private List<Evidence> evidence(
            WeeklyReviewFacts source,
            Projection core,
            SalesStructureBlock structure,
            TeamBlock team,
            List<EmployeeCard> employees,
            boolean sourceAnalyticsBlocked
    ) {
        EvidenceCollector collector = new EvidenceCollector(source);
        core.results().forEach(metric -> collector.metric(metric, "STORE", null));
        RevenueDecomposition revenue = core.revenueDecomposition();
        collector.metric(revenue.salesRevenue(), "STORE", null);
        collector.metric(revenue.returnRevenue(), "STORE", null);
        collector.metric(revenue.netRevenue(), "STORE", null);
        collector.metric(revenue.saleDocumentCount(), "STORE", null);
        collector.metric(revenue.returnDocumentCount(), "STORE", null);
        collectStructure(collector, structure.root());
        structure.attachMetrics().forEach(metric -> collector.metric(
                metric.comparison(), "STORE", null
        ));
        employees.forEach(employee -> collectEmployee(collector, employee));
        team.observations().forEach(collector::teamObservation);
        if (!sourceAnalyticsBlocked) {
            collector.employeeAttribution();
        }
        return collector.values();
    }

    private void collectStructure(EvidenceCollector collector, StructureNode node) {
        collector.metric(node.comparison(), "STORE", null);
        collector.metric(node.shareComparison(), "STORE", null);
        node.children().forEach(child -> collectStructure(collector, child));
    }

    private void collectEmployee(EvidenceCollector collector, EmployeeCard employee) {
        var metrics = employee.metrics();
        collector.metric(metrics.completedSales(), "EMPLOYEE", employee.employeePublicId());
        collector.metric(metrics.netRevenue(), "EMPLOYEE", employee.employeePublicId());
        collector.metric(metrics.additionalRevenue(), "EMPLOYEE", employee.employeePublicId());
        collector.metric(metrics.additionalShare(), "EMPLOYEE", employee.employeePublicId());
        collector.metric(metrics.shiftCount(), "EMPLOYEE", employee.employeePublicId());
        collector.metric(metrics.workedHours(), "EMPLOYEE", employee.employeePublicId());
        collector.metric(metrics.revenuePerHour(), "EMPLOYEE", employee.employeePublicId());
        metrics.attachMetrics().forEach(metric -> collector.metric(
                metric.comparison(), "EMPLOYEE", employee.employeePublicId()
        ));
        if (employee.peerComparison() != null) {
            collector.teamMedian(
                    employee.peerComparison().metricCode(),
                    employee.peerComparison().benchmarkValue()
            );
        }
    }

    private Map<String, String> attachLabels(WeeklyReviewFacts source) {
        Map<String, String> categoryNames = new LinkedHashMap<>();
        source.previous().categories().categories().forEach(category -> categoryNames.put(
                category.categoryCode(), category.categoryName()
        ));
        source.current().categories().categories().forEach(category -> categoryNames.put(
                category.categoryCode(), category.categoryName()
        ));
        Map<String, String> result = new LinkedHashMap<>();
        source.previous().attachRates().rates().forEach(rate -> result.put(
                rate.metricCode(),
                categoryNames.getOrDefault(rate.numeratorCategoryCode(), rate.metricCode())
        ));
        source.current().attachRates().rates().forEach(rate -> result.put(
                rate.metricCode(),
                categoryNames.getOrDefault(rate.numeratorCategoryCode(), rate.metricCode())
        ));
        return result;
    }

    private String comparisonText(MetricComparison metric) {
        if (metric.changePercent() == null) {
            return "";
        }
        String sign = metric.changePercent().signum() > 0 ? "+" : "";
        return " (" + sign + metric.changePercent().setScale(
                1, RoundingMode.HALF_UP
        ).toPlainString() + "%)";
    }

    private String format(BigDecimal value, Unit unit) {
        if (value == null) {
            return "нет данных";
        }
        return switch (unit) {
            case RUB -> value.setScale(0, RoundingMode.HALF_UP).toPlainString() + " ₽";
            case PERCENT -> value.setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
            case PER_100 -> value.setScale(1, RoundingMode.HALF_UP).toPlainString() + " на 100";
            case HOURS -> value.setScale(1, RoundingMode.HALF_UP).toPlainString() + " ч";
            default -> value.stripTrailingZeros().toPlainString();
        };
    }

    private record FactorCandidate(
            int priority,
            String kind,
            MetricComparison comparison,
            BigDecimal contributionAmount
    ) {
    }

    private static final class EvidenceCollector {

        private final WeeklyReviewFacts source;
        private final Map<String, Evidence> evidence = new LinkedHashMap<>();

        private EvidenceCollector(WeeklyReviewFacts source) {
            this.source = source;
        }

        private void metric(
                MetricComparison metric,
                String scope,
                String employeePublicId
        ) {
            for (String reference : metric.evidenceRefs()) {
                var currentSample = metric.currentSample();
                var previousSample = metric.previousSample();
                evidence.putIfAbsent(reference, new Evidence(
                        reference,
                        scope,
                        employeePublicId,
                        metric.code(),
                        metric.label(),
                        metric.unit(),
                        source.period().current(),
                        source.period().previous(),
                        metric.current(),
                        metric.previous(),
                        currentSample == null ? null : currentSample.numerator(),
                        currentSample == null ? null : currentSample.denominator(),
                        previousSample == null ? null : previousSample.numerator(),
                        previousSample == null ? null : previousSample.denominator(),
                        WeeklyReviewPolicyV1.VERSIONS.metricsPolicy(),
                        metric.sufficiency(),
                        metric.materiality(),
                        metric.metricState() != WeeklyReviewResponse.MetricState.UNAVAILABLE
                ));
            }
        }

        private void teamMedian(String metricCode, BigDecimal median) {
            String reference = "TEAM.MEDIAN." + metricCode;
            String label = "REVENUE_PER_HOUR".equals(metricCode)
                    ? "Медиана выручки в час сотрудников"
                    : "Медиана чистой выручки сотрудников";
            evidence.putIfAbsent(reference, new Evidence(
                    reference,
                    "TEAM",
                    null,
                    metricCode + "_MEDIAN",
                    label,
                    Unit.RUB,
                    source.period().current(),
                    source.period().previous(),
                    median,
                    null,
                    null,
                    null,
                    null,
                    null,
                    WeeklyReviewPolicyV1.VERSIONS.metricsPolicy(),
                    Sufficiency.SUFFICIENT,
                    Materiality.NOT_EVALUATED,
                    true
            ));
        }

        private void teamObservation(Observation observation) {
            observation.evidenceRefs().forEach(reference -> evidence.putIfAbsent(
                    reference,
                    new Evidence(
                            reference,
                            "TEAM",
                            null,
                            reference,
                            observation.title(),
                            Unit.STATUS,
                            source.period().current(),
                            source.period().previous(),
                            observation.detail(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            WeeklyReviewPolicyV1.VERSIONS.metricsPolicy(),
                            Sufficiency.SUFFICIENT,
                            MATERIAL,
                            true
                    )
            ));
        }

        private void employeeAttribution() {
            addAttributionEvidence(
                    "EMPLOYEE_ATTRIBUTION.CURRENT",
                    source.current().unattributedReturnDocumentCount(),
                    source.period().current()
            );
            addAttributionEvidence(
                    "EMPLOYEE_ATTRIBUTION.PREVIOUS",
                    source.previous().unattributedReturnDocumentCount(),
                    source.period().previous()
            );
        }

        private void addAttributionEvidence(
                String reference,
                long count,
                WeeklyReviewResponse.DateRange affectedPeriod
        ) {
            if (count <= 0) {
                return;
            }
            evidence.put(reference, new Evidence(
                    reference,
                    "TEAM",
                    null,
                    "UNATTRIBUTED_RETURN_DOCUMENT_COUNT",
                    "Возвраты без продавца исходной продажи",
                    Unit.COUNT,
                    affectedPeriod,
                    null,
                    BigDecimal.valueOf(count),
                    null,
                    null,
                    null,
                    null,
                    null,
                    WeeklyReviewPolicyV1.VERSIONS.metricsPolicy(),
                    Sufficiency.LIMITED,
                    Materiality.NOT_EVALUATED,
                    true
            ));
        }

        private List<Evidence> values() {
            return List.copyOf(evidence.values());
        }
    }
}
