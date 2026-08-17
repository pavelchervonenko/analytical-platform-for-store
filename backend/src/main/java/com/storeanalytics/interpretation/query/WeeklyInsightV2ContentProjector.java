package com.storeanalytics.interpretation.query;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.CandidateSignal;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Sufficiency;
import com.storeanalytics.interpretation.snapshot.PersistedWeeklySnapshot;
import com.storeanalytics.interpretation.snapshot.SnapshotEmployeeMembership;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Adapts the canonical flat v2 contract to the stable dashboard presentation model.
 */
final class WeeklyInsightV2ContentProjector {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    WeeklyInsightContentView project(
            JsonNode content,
            PersistedWeeklySnapshot snapshot
    ) {
        Map<String, SnapshotEmployeeMembership> employeesByRef = memberships(snapshot);
        Map<String, Integer> priorities = candidatePriorities(snapshot);
        List<WeeklyInsightEmployeeView> employees = new ArrayList<>();
        for (JsonNode descriptor : requiredArray(content, "employees")) {
            String employeeRef = requiredText(descriptor, "employeeRef");
            SnapshotEmployeeMembership membership = employeesByRef.get(employeeRef);
            if (membership == null) {
                throw new IllegalStateException(
                        "Published interpretation employee is outside its snapshot"
                );
            }
            employees.add(new WeeklyInsightEmployeeView(
                    membership.employeeId(),
                    membership.displayNameSnapshot(),
                    requiredText(descriptor, "analysisStatus"),
                    employeeInsight(content, descriptor, priorities)
            ));
        }
        if (employees.size() != snapshot.employees().size()) {
            throw new IllegalStateException(
                    "Published interpretation employee set is incomplete"
            );
        }
        return new WeeklyInsightContentView(
                storeInsight(content, priorities),
                teamInsight(content, employeesByRef, priorities),
                employees,
                requiredArray(content, "dataLimitations")
        );
    }

    private Map<String, Integer> candidatePriorities(
            PersistedWeeklySnapshot snapshot
    ) {
        List<CandidateSignal> candidates = new ArrayList<>(
                snapshot.payload().facts().candidateSignals()
        );
        candidates.sort(Comparator
                .comparingInt(this::sufficiencyPriority)
                .thenComparingInt(this::themePriority)
                .thenComparing(CandidateSignal::candidateRef));
        Map<String, Integer> result = new HashMap<>();
        for (int index = 0; index < candidates.size(); index++) {
            result.put(candidates.get(index).candidateRef(), index);
        }
        return Map.copyOf(result);
    }

    private int sufficiencyPriority(CandidateSignal candidate) {
        return candidate.sufficiency() == Sufficiency.SUFFICIENT ? 0
                : candidate.sufficiency() == Sufficiency.LIMITED ? 1 : 2;
    }

    private int themePriority(CandidateSignal candidate) {
        return themePriority(candidate.theme(), candidate.employeeRef() != null);
    }

    private Map<String, SnapshotEmployeeMembership> memberships(
            PersistedWeeklySnapshot snapshot
    ) {
        Map<String, SnapshotEmployeeMembership> result = new HashMap<>();
        for (SnapshotEmployeeMembership membership : snapshot.employees()) {
            result.put(membership.employeeRef(), membership);
        }
        return result;
    }

    private ObjectNode storeInsight(
            JsonNode root,
            Map<String, Integer> priorities
    ) {
        List<JsonNode> insights = insights(root, "STORE", null, priorities);
        ObjectNode result = NODES.objectNode();
        result.set("headline", requiredNarrative(root, "STORE", null, "HEADLINE"));
        result.set("resultSummary", narrative(root, "STORE", null, "RESULT"));
        result.set("dynamicsSummary", narrative(root, "STORE", null, "DYNAMICS"));
        result.set(
                "categoryPerformance",
                categoryPerformance(root, "STORE", null, insights)
        );
        result.set(
                "additionalSalesPerformance",
                additionalSalesPerformance(root, "STORE", null, insights)
        );
        result.set("planOutlook", narrative(root, "STORE", null, "PLAN_OUTLOOK"));
        result.set("strength", highestPriorityInsight(
                insights,
                insight -> kindIs(insight, "OBSERVATION", "OPPORTUNITY")
                        && !themeIs(insight, "DATA_QUALITY")
        ));
        result.set("attentionArea", highestPriorityInsight(
                insights,
                insight -> kindIs(insight, "SYNTHESIS", "HYPOTHESIS")
        ));
        result.set("primaryRisk", highestPriorityInsight(
                insights,
                insight -> kindIs(insight, "RISK")
        ));
        result.set("recommendedActions", actions(
                root,
                action -> !"EMPLOYEE".equals(action.path("targetScope").asText())
        ));
        return result;
    }

    private ObjectNode teamInsight(
            JsonNode root,
            Map<String, SnapshotEmployeeMembership> employeesByRef,
            Map<String, Integer> priorities
    ) {
        ObjectNode result = NODES.objectNode();
        result.set(
                "summary",
                requiredNarrative(root, "TEAM", null, "TEAM_OVERVIEW")
        );
        result.set("highlights", projectedInsights(insights(
                root, "TEAM", null, priorities
        )));

        ArrayNode competencyLeaders = NODES.arrayNode();
        ArrayNode mostImproved = NODES.arrayNode();
        ArrayNode learningOpportunities = NODES.arrayNode();
        for (JsonNode relationship : requiredArray(root, "teamRelationships")) {
            switch (relationship.path("type").asText()) {
                case "COMPETENCY_LEADER" ->
                        competencyLeaders.add(competencyLeader(
                                relationship, employeesByRef
                        ));
                case "MOST_IMPROVED" ->
                        addMostImproved(
                                mostImproved, relationship, employeesByRef
                        );
                case "LEARNING_OPPORTUNITY" ->
                        learningOpportunities.add(learningOpportunity(
                                relationship, employeesByRef
                        ));
                default -> throw new IllegalStateException(
                        "Unsupported team relationship type"
                );
            }
        }
        result.set("competencyLeaders", competencyLeaders);
        result.set("mostImproved", mostImproved);
        result.set("learningOpportunities", learningOpportunities);
        return result;
    }

    private ObjectNode employeeInsight(
            JsonNode root,
            JsonNode descriptor,
            Map<String, Integer> priorities
    ) {
        String employeeRef = requiredText(descriptor, "employeeRef");
        List<JsonNode> insights = insights(
                root, "EMPLOYEE", employeeRef, priorities
        );
        ObjectNode result = NODES.objectNode();
        result.put("analysisStatus", requiredText(descriptor, "analysisStatus"));
        result.set(
                "headline",
                requiredNarrative(root, "EMPLOYEE", employeeRef, "HEADLINE")
        );
        result.set(
                "workloadContext",
                narrative(root, "EMPLOYEE", employeeRef, "WORKLOAD")
        );
        result.set(
                "performanceSummary",
                narrative(root, "EMPLOYEE", employeeRef, "RESULT")
        );
        result.set(
                "dynamicsSummary",
                narrative(root, "EMPLOYEE", employeeRef, "DYNAMICS")
        );
        result.set(
                "categoryPerformance",
                categoryPerformance(root, "EMPLOYEE", employeeRef, insights)
        );
        result.set(
                "additionalSalesPerformance",
                additionalSalesPerformance(
                        root, "EMPLOYEE", employeeRef, insights
                )
        );
        result.set("strength", highestPriorityInsight(
                insights,
                insight -> kindIs(insight, "OBSERVATION", "OPPORTUNITY")
                        && !themeIs(insight, "DATA_QUALITY")
        ));
        result.set("attentionArea", highestPriorityInsight(
                insights,
                insight -> kindIs(insight, "SYNTHESIS", "HYPOTHESIS")
        ));
        result.set("primaryRisk", highestPriorityInsight(
                insights,
                insight -> kindIs(insight, "RISK")
        ));
        result.set("recommendedActions", actions(
                root,
                action -> contains(
                        action.path("targetEmployeeRefs"), employeeRef
                )
        ));
        result.set("dataLimitations", limitations(root, employeeRef));
        return result;
    }

    private JsonNode categoryPerformance(
            JsonNode root,
            String scope,
            String employeeRef,
            List<JsonNode> insights
    ) {
        JsonNode summary = narrative(
                root, scope, employeeRef, "CATEGORY_PERFORMANCE"
        );
        List<JsonNode> categoryInsights = insights.stream()
                .filter(this::isCategoryInsight)
                .toList();
        if (summary.isNull() && categoryInsights.isEmpty()) {
            return NODES.nullNode();
        }
        ObjectNode result = NODES.objectNode();
        result.set("summary", summary);
        if ("EMPLOYEE".equals(scope)) {
            result.set("strengths", projectedInsights(categoryInsights.stream()
                    .filter(value -> kindIs(value, "OBSERVATION", "OPPORTUNITY"))
                    .toList()));
            result.set("attentionAreas", projectedInsights(categoryInsights.stream()
                    .filter(value -> kindIs(value, "RISK", "HYPOTHESIS"))
                    .toList()));
            result.set("dynamics", projectedInsights(categoryInsights.stream()
                    .filter(value -> kindIs(value, "SYNTHESIS"))
                    .toList()));
        } else {
            result.set("growthDrivers", projectedInsights(categoryInsights.stream()
                    .filter(value -> kindIs(value, "OBSERVATION", "OPPORTUNITY"))
                    .toList()));
            result.set("declineDrivers", projectedInsights(categoryInsights.stream()
                    .filter(value -> kindIs(value, "RISK"))
                    .toList()));
            result.set("mixInsights", projectedInsights(categoryInsights.stream()
                    .filter(value -> kindIs(value, "SYNTHESIS", "HYPOTHESIS"))
                    .toList()));
        }
        return result;
    }

    private JsonNode additionalSalesPerformance(
            JsonNode root,
            String scope,
            String employeeRef,
            List<JsonNode> insights
    ) {
        JsonNode summary = narrative(
                root, scope, employeeRef, "ADDITIONAL_SALES"
        );
        List<JsonNode> additional = insights.stream()
                .filter(this::isAdditionalSalesInsight)
                .toList();
        if (summary.isNull() && additional.isEmpty()) {
            return NODES.nullNode();
        }
        ObjectNode result = NODES.objectNode();
        result.set("summary", summary);
        result.set("revenueInsights", projectedInsights(additional.stream()
                .filter(value -> !themeIs(value, "ATTACH_RATE")
                        && !kindIs(value, "OPPORTUNITY"))
                .toList()));
        result.set("attachRateInsights", projectedInsights(additional.stream()
                .filter(value -> themeIs(value, "ATTACH_RATE")
                        && !kindIs(value, "OPPORTUNITY"))
                .toList()));
        result.set("opportunities", projectedInsights(additional.stream()
                .filter(value -> kindIs(value, "OPPORTUNITY"))
                .toList()));
        return result;
    }

    private boolean isCategoryInsight(JsonNode insight) {
        return (!insight.path("categoryCode").isNull()
                || themeIs(insight, "CATEGORY_MIX"))
                && !isAdditionalSalesInsight(insight);
    }

    private boolean isAdditionalSalesInsight(JsonNode insight) {
        return themeIs(insight, "ADDITIONAL_SALES", "ATTACH_RATE");
    }

    private ObjectNode competencyLeader(
            JsonNode relationship,
            Map<String, SnapshotEmployeeMembership> employeesByRef
    ) {
        ObjectNode result = NODES.objectNode();
        copy(result, relationship, "competencyCode");
        result.set("employeeRefs", relationship.path("sourceEmployeeRefs").deepCopy());
        result.set(
                "employeeNames",
                employeeNames(relationship.path("sourceEmployeeRefs"), employeesByRef)
        );
        copy(result, relationship, "summary");
        copy(result, relationship, "evidenceRefs");
        return result;
    }

    private void addMostImproved(
            ArrayNode target,
            JsonNode relationship,
            Map<String, SnapshotEmployeeMembership> employeesByRef
    ) {
        for (JsonNode employeeRef : relationship.path("sourceEmployeeRefs")) {
            ObjectNode result = target.addObject();
            result.put("employeeRef", employeeRef.asText());
            SnapshotEmployeeMembership membership = employeesByRef.get(
                    employeeRef.asText()
            );
            if (membership != null) {
                result.put("displayName", membership.displayNameSnapshot());
            }
            result.put("kind", "OBSERVATION");
            copy(result, relationship, "summary");
            copy(result, relationship, "evidenceRefs");
        }
    }

    private ObjectNode learningOpportunity(
            JsonNode relationship,
            Map<String, SnapshotEmployeeMembership> employeesByRef
    ) {
        ObjectNode result = NODES.objectNode();
        copy(result, relationship, "competencyCode");
        result.set(
                "mentorEmployeeRefs",
                relationship.path("sourceEmployeeRefs").deepCopy()
        );
        result.set(
                "targetEmployeeRefs",
                relationship.path("targetEmployeeRefs").deepCopy()
        );
        result.set(
                "mentorNames",
                employeeNames(relationship.path("sourceEmployeeRefs"), employeesByRef)
        );
        result.set(
                "targetNames",
                employeeNames(relationship.path("targetEmployeeRefs"), employeesByRef)
        );
        copy(result, relationship, "summary");
        copy(result, relationship, "evidenceRefs");
        return result;
    }

    private JsonNode requiredNarrative(
            JsonNode root,
            String scope,
            String employeeRef,
            String section
    ) {
        JsonNode value = narrative(root, scope, employeeRef, section);
        if (value.isNull()) {
            throw new IllegalStateException(
                    "Published interpretation is missing required summary: "
                            + scope + "/" + section
            );
        }
        return value;
    }

    private JsonNode narrative(
            JsonNode root,
            String scope,
            String employeeRef,
            String section
    ) {
        for (JsonNode summary : requiredArray(root, "summaryBlocks")) {
            if (scope.equals(summary.path("scope").asText())
                    && section.equals(summary.path("section").asText())
                    && nullableText(summary.path("employeeRef"))
                    .equals(employeeRef == null ? "" : employeeRef)) {
                ObjectNode result = NODES.objectNode();
                copy(result, summary, "text");
                copy(result, summary, "evidenceRefs");
                return result;
            }
        }
        return NODES.nullNode();
    }

    private List<JsonNode> insights(
            JsonNode root,
            String scope,
            String employeeRef,
            Map<String, Integer> priorities
    ) {
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode insight : requiredArray(root, "insights")) {
            if (scope.equals(insight.path("scope").asText())
                    && nullableText(insight.path("employeeRef"))
                    .equals(employeeRef == null ? "" : employeeRef)) {
                result.add(insight);
            }
        }
        result.sort(Comparator.comparingInt(insight ->
                priorities.getOrDefault(
                        nullableText(insight.path("candidateRef")), Integer.MAX_VALUE
                )));
        return result;
    }

    private ArrayNode projectedInsights(List<JsonNode> insights) {
        ArrayNode result = NODES.arrayNode();
        insights.forEach(value -> result.add(projectedInsight(value)));
        return result;
    }

    private ObjectNode projectedInsight(JsonNode insight) {
        ObjectNode result = NODES.objectNode();
        copy(result, insight, "kind");
        copy(result, insight, "theme");
        copy(result, insight, "candidateRef");
        copy(result, insight, "title");
        copy(result, insight, "summary");
        copy(result, insight, "evidenceRefs");
        return result;
    }

    private JsonNode highestPriorityInsight(
            List<JsonNode> insights,
            Predicate<JsonNode> predicate
    ) {
        return insights.stream()
                .filter(predicate)
                .findFirst()
                .map(this::projectedInsight)
                .map(JsonNode.class::cast)
                .orElseGet(NODES::nullNode);
    }

    private int themePriority(String theme, boolean employee) {
        if (employee) {
            return switch (theme) {
                case "EMPLOYEE_PERFORMANCE" -> 0;
                case "TIME_EFFICIENCY" -> 1;
                case "ADDITIONAL_SALES" -> 2;
                case "ATTACH_RATE" -> 3;
                case "CATEGORY_MIX" -> 4;
                case "PLAN" -> 5;
                default -> 6;
            };
        }
        return switch (theme) {
            case "PLAN" -> 0;
            case "PROFITABILITY" -> 1;
            case "REVENUE_DYNAMICS" -> 2;
            case "ADDITIONAL_SALES" -> 3;
            case "ATTACH_RATE" -> 4;
            case "CATEGORY_MIX" -> 5;
            case "TEAM_PERFORMANCE" -> 6;
            default -> 7;
        };
    }

    private ArrayNode actions(JsonNode root, Predicate<JsonNode> predicate) {
        ArrayNode result = NODES.arrayNode();
        for (JsonNode action : requiredArray(root, "actions")) {
            if (predicate.test(action)) {
                result.add(action.deepCopy());
            }
        }
        return result;
    }

    private ArrayNode limitations(JsonNode root, String employeeRef) {
        ArrayNode result = NODES.arrayNode();
        for (JsonNode limitation : requiredArray(root, "dataLimitations")) {
            if (employeeRef.equals(nullableText(limitation.path("employeeRef")))) {
                result.add(limitation.deepCopy());
            }
        }
        return result;
    }

    private ArrayNode employeeNames(
            JsonNode employeeRefs,
            Map<String, SnapshotEmployeeMembership> employeesByRef
    ) {
        ArrayNode names = NODES.arrayNode();
        for (JsonNode employeeRef : employeeRefs) {
            SnapshotEmployeeMembership membership = employeesByRef.get(
                    employeeRef.asText()
            );
            if (membership != null) {
                names.add(membership.displayNameSnapshot());
            }
        }
        return names;
    }

    private boolean kindIs(JsonNode insight, String... values) {
        return matches(insight.path("kind").asText(), values);
    }

    private boolean themeIs(JsonNode insight, String... values) {
        return matches(insight.path("theme").asText(), values);
    }

    private boolean matches(String actual, String... values) {
        for (String value : values) {
            if (value.equals(actual)) {
                return true;
            }
        }
        return false;
    }

    private boolean contains(JsonNode array, String value) {
        for (JsonNode item : array) {
            if (value.equals(item.asText())) {
                return true;
            }
        }
        return false;
    }

    private String nullableText(JsonNode value) {
        return value.isTextual() ? value.asText() : "";
    }

    private ArrayNode requiredArray(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!(value instanceof ArrayNode array)) {
            throw new IllegalStateException(
                    "Published interpretation field is not an array: " + field
            );
        }
        return array;
    }

    private String requiredText(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException(
                    "Published interpretation field is not text: " + field
            );
        }
        return value.asText();
    }

    private void copy(ObjectNode target, JsonNode source, String field) {
        target.set(field, source.path(field).deepCopy());
    }
}
