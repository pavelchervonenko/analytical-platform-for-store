package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.require;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Action;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.AttachMetric;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.EmployeeMetricSet;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricComparison;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.NarrativeItem;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Observation;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.StructureNode;
import java.util.List;
import java.util.Locale;

/** Defense-in-depth: a v3 envelope must not carry legacy STORE identifiers or scopes. */
final class WeeklyReviewV3ScopeValidator {

    private WeeklyReviewV3ScopeValidator() {
    }

    static void validate(WeeklyReviewV3Response response) {
        response.sourceCoverage().forEach(item -> checkAll(item.affectedBlockIds()));
        check(response.summary().blockId());
        narrative(response.summary().outcome());
        narrative(response.summary().positive());
        narrative(response.summary().risk());
        response.results().forEach(WeeklyReviewV3ScopeValidator::metric);
        var revenue = response.revenueDecomposition();
        metric(revenue.salesRevenue());
        metric(revenue.returnRevenue());
        metric(revenue.netRevenue());
        metric(revenue.saleDocumentCount());
        metric(revenue.returnDocumentCount());
        metric(response.additionalSales().revenue());
        metric(response.additionalSales().shareOfSellerRevenue());
        response.factors().forEach(factor -> {
            check(factor.factorId());
            check(factor.kind());
            metric(factor.comparison());
            checkAll(factor.evidenceRefs());
        });
        check(response.salesStructure().blockId());
        checkAll(response.salesStructure().limitations());
        structure(response.salesStructure().root());
        response.salesStructure().attachMetrics().forEach(WeeklyReviewV3ScopeValidator::attach);
        check(response.team().blockId());
        response.team().observations().forEach(WeeklyReviewV3ScopeValidator::observation);
        response.employees().forEach(employee -> {
            var card = employee.card();
            check(card.sortGroup());
            checkAll(card.limitations());
            employeeMetrics(card.metrics());
            card.ownDynamics().forEach(WeeklyReviewV3ScopeValidator::observation);
            if (card.peerComparison() != null) {
                check(card.peerComparison().metricCode());
                checkAll(card.peerComparison().evidenceRefs());
            }
            observation(card.strength());
            observation(card.attention());
            action(card.action());
        });
        response.actions().forEach(WeeklyReviewV3ScopeValidator::action);
        response.limitations().forEach(limitation -> {
            check(limitation.scope());
            check(limitation.code());
            checkAll(limitation.affectedBlockIds());
            checkAll(limitation.affectedMetricCodes());
            checkAll(limitation.evidenceRefs());
        });
        response.evidence().forEach(item -> {
            check(item.scope());
            check(item.evidenceRef());
            check(item.metricCode());
        });
    }

    private static void narrative(NarrativeItem item) {
        if (item != null) {
            check(item.itemId());
            checkAll(item.evidenceRefs());
        }
    }

    private static void metric(MetricComparison item) {
        check(item.metricId());
        check(item.code());
        checkAll(item.evidenceRefs());
    }

    private static void structure(StructureNode node) {
        check(node.nodeId());
        check(node.code());
        metric(node.comparison());
        metric(node.shareComparison());
        node.children().forEach(WeeklyReviewV3ScopeValidator::structure);
    }

    private static void attach(AttachMetric item) {
        check(item.metricId());
        check(item.code());
        metric(item.comparison());
    }

    private static void employeeMetrics(EmployeeMetricSet metrics) {
        metric(metrics.completedSales());
        metric(metrics.netRevenue());
        metric(metrics.additionalRevenue());
        metric(metrics.additionalShare());
        metric(metrics.shiftCount());
        metric(metrics.workedHours());
        metric(metrics.revenuePerHour());
        metrics.attachMetrics().forEach(WeeklyReviewV3ScopeValidator::attach);
    }

    private static void observation(Observation item) {
        if (item != null) {
            check(item.observationId());
            checkAll(item.evidenceRefs());
        }
    }

    private static void action(Action item) {
        if (item != null) {
            check(item.scope());
            check(item.actionType());
            check(item.metricCode());
            checkAll(item.evidenceRefs());
        }
    }

    private static void checkAll(List<String> values) {
        values.forEach(WeeklyReviewV3ScopeValidator::check);
    }

    private static void check(String identifier) {
        if (identifier != null) {
            String canonical = identifier.toUpperCase(Locale.ROOT);
            require(!canonical.equals("STORE") && !canonical.startsWith("STORE.")
                            && !canonical.startsWith("STORE:")
                            && !canonical.startsWith("STORE_"),
                    "v3 payload contains a legacy STORE identifier or scope");
        }
    }
}
