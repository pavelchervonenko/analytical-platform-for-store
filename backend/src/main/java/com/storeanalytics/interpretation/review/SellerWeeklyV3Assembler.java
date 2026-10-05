package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.AiState.DISABLED;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.Effect.NEGATIVE;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.Effect.POSITIVE;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.GeneratedBy.DETERMINISTIC;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.Materiality.MATERIAL;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricState.UNAVAILABLE;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.CoverageState.COMPLETE;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Action;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.ActionTarget;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.AiEnhancement;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Evidence;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.CoverageState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Factor;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Limitation;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricComparison;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Provenance;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.QualitySummary;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.ReportState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.StructureNode;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.VersionSet;
import com.storeanalytics.metrics.service.SellerCohortSnapshot;
import java.time.Instant;
import java.util.Set;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds a seller-only v3 candidate; publishing still requires the source identity and coverage gates. */
final class SellerWeeklyV3Assembler {

    private static final VersionSet VERSIONS = new VersionSet(
            "weekly-metrics-v9-sellers-return-processor", "weekly-snapshot-v17", "weekly-quality-v11");
    private static final VersionSet HISTORICAL_VERSIONS = new VersionSet(
            "weekly-metrics-v10-sellers-historical", "weekly-snapshot-v18", "weekly-quality-v11");

    static VersionSet versions() {
        return VERSIONS;
    }

    static VersionSet historicalVersions() {
        return HISTORICAL_VERSIONS;
    }

    private final SellerWeeklyReviewProjector projector = new SellerWeeklyReviewProjector();
    private final SellerWeeklyV3TeamPresenter teamPresenter = new SellerWeeklyV3TeamPresenter();
    private final WeeklyReviewSummaryPresenter summaryPresenter = new WeeklyReviewSummaryPresenter();

    WeeklyReviewV3Response assemble(SellerWeeklyReviewFacts facts, Provenance provenance,
            String sourceIdentityHash, Instant actionabilityAsOf) {
        SellerWeeklyReviewFacts source = requireNonNull(facts, "facts");
        SellerWeeklyReviewProjector.Projection projected = projector.project(source);
        SellerCohortSnapshot cohort = source.comparison().current().metrics().cohort();
        var membership = new WeeklyReviewV3Response.Membership(SellerCohortSnapshot.BASIS,
                cohort.fingerprint(), cohort.fingerprint(), cohort.fingerprint(),
                requireNonNull(actionabilityAsOf, "actionabilityAsOf"), cohort.employeeIds().size());
        return assemble(new AssemblyContext(source.period(), source.sourceCoverage(), VERSIONS, true),
                projected, people(projected, Set.copyOf(cohort.employeeIds())), membership, provenance,
                sourceIdentityHash);
    }

    WeeklyReviewV3Response assembleHistorical(SellerWeeklyHistoricalFacts facts, Provenance provenance,
            String sourceIdentityHash, Instant actionabilityAsOf) {
        SellerWeeklyHistoricalFacts source = requireNonNull(facts, "facts");
        Instant asOf = requireNonNull(actionabilityAsOf, "actionabilityAsOf");
        var projected = projector.projectHistorical(source);
        var selected = source.membership();
        var cohort = source.historical().comparison().current().metrics().cohort();
        var membership = new WeeklyReviewV3Response.Membership(SellerWeeklyHistoricalMembership.BASIS,
                selected.selectionHash(), selected.selectionHash(), selected.actionabilityHash(),
                asOf, cohort.employeeIds().size());
        boolean latest = ClosedSellerWeek.latest(asOf, source.period().timezone()).start()
                .equals(source.period().current().start());
        var people = people(projected, source.historical().actionEmployeeIds());
        if (!latest) {
            people = withoutFutureActions(people);
        }
        return assemble(new AssemblyContext(source.period(), source.sourceCoverage(), HISTORICAL_VERSIONS, latest),
                projected, people, membership, provenance, sourceIdentityHash);
    }

    private SellerWeeklyV3TeamPresenter.Projection people(SellerWeeklyReviewProjector.Projection projected,
            Set<java.util.UUID> actionIds) {
        boolean complete = projected.quality().sourceCoverage().stream()
                .filter(item -> item.requiredForReport()).allMatch(item -> item.state() == COMPLETE);
        return !complete ? teamPresenter.unavailable()
                : projected.teamFacts().map(facts -> teamPresenter.presentHistorical(facts,
                        projected.returnAttributionComplete(), projected.additionalSales().additionalRevenue()
                                .metricState() == WeeklyReviewResponse.MetricState.READY, actionIds))
                        .orElseGet(teamPresenter::unavailable);
    }

    private SellerWeeklyV3TeamPresenter.Projection withoutFutureActions(SellerWeeklyV3TeamPresenter.Projection people) {
        var cards = people.cards().stream().map(item -> {
            var card = item.card();
            var withoutAction = new WeeklyReviewResponse.EmployeeCard(card.employeePublicId(), card.displayName(),
                    card.participatesInBenchmark(), card.sortGroup(), card.metrics(), card.ownDynamics(),
                    card.peerComparison(), card.strength(), card.attention(), null, card.limitations());
            return new WeeklyReviewV3Response.EmployeeCard(withoutAction, item.actionableNow());
        }).toList();
        return new SellerWeeklyV3TeamPresenter.Projection(people.team(), people.display(), cards);
    }

    private WeeklyReviewV3Response assemble(AssemblyContext source, SellerWeeklyReviewProjector.Projection projected,
            SellerWeeklyV3TeamPresenter.Projection people, WeeklyReviewV3Response.Membership membership,
            Provenance provenance, String sourceIdentityHash) {
        boolean blocked = projected.quality().reportState() == ReportState.BLOCKED;
        var additional = projected.additionalSales();
        var additionalSales = new WeeklyReviewV3Response.AdditionalSales(
                additional.additionalRevenue(), additional.additionalShare(),
                additional.accessoryRevenue(), additional.serviceRevenue(),
                additional.accessoryMixShare(), additional.serviceMixShare(),
                additional.integrityResidual(), additional.compositionChartSafe());
        List<Factor> factors = blocked ? List.of() : factors(projected);
        List<Action> actions = blocked || !source.allowFutureActions() ? List.of() : actions(factors);
        List<Limitation> limitations = projected.quality().limitations();
        ReportState reportState = projected.quality().reportState();
        QualitySummary qualitySummary = projected.quality().qualitySummary();
        List<Evidence> evidence = evidence(source, projected, additionalSales, people);
        var response = new WeeklyReviewV3Response(3, source.versions(), source.period(),
                requireNonNull(provenance, "provenance"), reportState, qualitySummary,
                coverage(source, projected), "SELLERS", membership, sourceIdentityHash,
                summaryPresenter.present(reportState, projected.core().results(), factors,
                        projected.returnAttributionComplete()),
                projected.core().results(), projected.core().revenueDecomposition(),
                additionalSales, factors, projected.structure(), people.team(), people.display(),
                people.cards(), actions, limitations, evidence,
                new AiEnhancement(DISABLED, null, null, null));
        WeeklyReviewV3ScopeValidator.validate(response);
        return response;
    }

    private List<WeeklyReviewV3Response.SellerSourceCoverage> coverage(
            AssemblyContext source, SellerWeeklyReviewProjector.Projection projected) {
        var coverage = new ArrayList<>(projected.quality().sourceCoverage().stream()
                .map(WeeklyReviewV3Response.SellerSourceCoverage::from).toList());
        var orders = source.sourceCoverage().orders();
        CoverageState state = orders.current() && orders.previous() ? CoverageState.COMPLETE
                : orders.current() || orders.previous() ? CoverageState.PARTIAL : CoverageState.MISSING;
        coverage.add(new WeeklyReviewV3Response.SellerSourceCoverage("ORDERS", true,
                List.of("summary", "results", "sales-structure", "team", "employees"),
                orders.current() ? source.period().current().end() : null,
                orders.previous() ? source.period().previous().end() : null, state,
                state == CoverageState.COMPLETE ? "Заказы загружены за обе недели."
                        : "Полнота заказов за обе недели не подтверждена."));
        return List.copyOf(coverage);
    }

    private List<Factor> factors(SellerWeeklyReviewProjector.Projection projection) {
        List<MetricComparison> candidates = List.of(
                projection.core().revenueDecomposition().returnRevenue(),
                projection.additionalSales().additionalRevenue());
        return candidates.stream().filter(item -> item.metricState() != UNAVAILABLE)
                .filter(item -> item.materiality() == MATERIAL)
                .filter(item -> item.effect() == POSITIVE || item.effect() == NEGATIVE)
                .limit(2).map(item -> new Factor("factor:" + item.code().toLowerCase(Locale.ROOT),
                        "SELLER_RESULT_CHANGE", item.label() + " изменилась",
                        "Сравните показатель продавцов с предыдущей неделей.", item,
                        "RETURN_REVENUE".equals(item.code()) && item.absoluteDelta() != null
                                ? item.absoluteDelta().negate() : item.absoluteDelta(),
                        item.effect(), item.evidenceRefs())).toList();
    }

    private List<Action> actions(List<Factor> factors) {
        return factors.stream().filter(item -> item.effect() == NEGATIVE)
                .filter(item -> item.comparison().previous() != null)
                .map(item -> {
                    MetricComparison metric = item.comparison();
                    boolean returns = "RETURN_REVENUE".equals(metric.code());
                    return new Action("action:team:" + metric.code().toLowerCase(Locale.ROOT),
                            "HIGH", "REVIEW_SELLER_METRIC", "TEAM", null,
                            returns ? "Разобрать возвраты продавцов" : "Разобрать динамику допов",
                            metric.code(), new ActionTarget(returns ? "AT_MOST" : "AT_LEAST",
                                    metric.previous(), metric.unit()),
                            "Проверить показатель по завершении следующей полной недели.",
                            "NEXT_FULL_WEEK", DETERMINISTIC, item.evidenceRefs());
                }).limit(3).toList();
    }

    private List<Evidence> evidence(AssemblyContext source,
            SellerWeeklyReviewProjector.Projection projection,
            WeeklyReviewV3Response.AdditionalSales additional,
            SellerWeeklyV3TeamPresenter.Projection people) {
        Map<String, Evidence> collected = new LinkedHashMap<>();
        projection.core().results().forEach(item -> add(collected, source, item, "SELLERS", null));
        var revenue = projection.core().revenueDecomposition();
        List.of(revenue.salesRevenue(), revenue.returnRevenue(), revenue.netRevenue(),
                revenue.saleDocumentCount(), revenue.returnDocumentCount(),
                additional.revenue(), additional.shareOfSellerRevenue())
                .forEach(item -> add(collected, source, item, "SELLERS", null));
        structure(collected, source, projection.structure().root());
        projection.structure().attachMetrics().forEach(item ->
                add(collected, source, item.comparison(), "SELLERS", null));
        people.cards().forEach(item -> {
            var card = item.card();
            var metrics = card.metrics();
            List.of(metrics.completedSales(), metrics.netRevenue(), metrics.additionalRevenue(),
                    metrics.additionalShare(), metrics.shiftCount(), metrics.workedHours(),
                    metrics.revenuePerHour()).forEach(metric ->
                    add(collected, source, metric, "EMPLOYEE", card.employeePublicId()));
        });
        return List.copyOf(collected.values());
    }

    private void structure(Map<String, Evidence> target, AssemblyContext source,
            StructureNode node) {
        add(target, source, node.comparison(), "SELLERS", null);
        add(target, source, node.shareComparison(), "SELLERS", null);
        node.children().forEach(child -> structure(target, source, child));
    }

    private void add(Map<String, Evidence> target, AssemblyContext source,
            MetricComparison metric, String scope, String employeeId) {
        for (String ref : metric.evidenceRefs()) {
            var currentSample = metric.currentSample();
            var previousSample = metric.previousSample();
            Evidence item = new Evidence(ref, scope, employeeId, metric.code(), metric.label(),
                    metric.unit(), source.period().current(), source.period().previous(),
                    metric.current(), metric.previous(),
                    currentSample == null ? null : currentSample.numerator(),
                    currentSample == null ? null : currentSample.denominator(),
                    previousSample == null ? null : previousSample.numerator(),
                    previousSample == null ? null : previousSample.denominator(),
                    source.versions().metricsPolicy(), metric.sufficiency(), metric.materiality(),
                    metric.metricState() != UNAVAILABLE && metric.current() != null);
            target.putIfAbsent(ref, item);
        }
    }

    private record AssemblyContext(WeeklyReviewResponse.PeriodContext period, SellerWeeklySourceCoverage sourceCoverage,
            VersionSet versions, boolean allowFutureActions) { }
}
