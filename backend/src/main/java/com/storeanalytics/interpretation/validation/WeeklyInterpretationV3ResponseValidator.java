package com.storeanalytics.interpretation.validation;

import com.storeanalytics.interpretation.contract.LlmContractResources;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput;
import com.storeanalytics.interpretation.contract.WeeklyPrimarySignalPolicy;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.CandidateSignal;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.EvidenceIndexEntry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Validates the primary-signal WeeklyInterpretationContent v3 contract. */
@Component
public final class WeeklyInterpretationV3ResponseValidator
        extends WeeklyInterpretationV2ResponseValidator {


    public WeeklyInterpretationV3ResponseValidator() {
        super(
                LlmContractResources.PRIMARY_SIGNAL_CONTENT_SCHEMA,
                LlmContractResources.PRIMARY_SIGNAL_CONTENT_SCHEMA_VERSION
        );
    }

    @Override
    protected void normalizeVersionSpecificFields(
            ObjectNode root,
            WeeklyInterpretationInput input
    ) {
        normalizeStructuredSummaryTransport(root, input);
        if (!(root.path("primarySignal") instanceof ObjectNode primarySignal)) {
            return;
        }
        putNullIfMissing(primarySignal, "employeeRef");
        putNullIfMissing(primarySignal, "categoryCode");
        Map<String, CandidateSignal> candidates = new HashMap<>();
        input.facts().candidateSignals().forEach(candidate ->
                candidates.put(candidate.candidateRef(), candidate)
        );
        CandidateSignal candidate = candidates.get(
                nullableText(primarySignal.path("candidateRef"))
        );
        if (candidate == null) {
            return;
        }
        Map<String, EvidenceIndexEntry> evidence = evidence(input);
        primarySignal.put("kind", candidate.kind().name());
        primarySignal.put("theme", candidate.theme());
        nullable(primarySignal, "employeeRef", candidate.employeeRef());
        nullable(primarySignal, "categoryCode", candidate.categoryCode());
        primarySignal.put("scope", candidateScope(candidate, evidence));
        var exactEvidence = primarySignal.putArray("evidenceRefs");
        candidate.evidenceRefs().forEach(exactEvidence::add);
    }

    private void normalizeStructuredSummaryTransport(
            ObjectNode root,
            WeeklyInterpretationInput input
    ) {
        boolean structuredTransport = root.has("teamOverview")
                || root.has("employeeHeadlines")
                || root.has("supportingSummaries");
        if (!structuredTransport) {
            return;
        }

        Map<String, WeeklyInterpretationInput.Sufficiency> statuses =
                new HashMap<>();
        input.facts().employees().forEach(employee ->
                statuses.put(employee.employeeRef(), employee.analysisStatus())
        );
        ArrayNode employees = root.putArray("employees");
        for (String employeeRef : input.manifest().employeeRefs()) {
            WeeklyInterpretationInput.Sufficiency status =
                    statuses.get(employeeRef);
            if (status != null) {
                ObjectNode employee = employees.addObject();
                employee.put("employeeRef", employeeRef);
                employee.put("analysisStatus", status.name());
            }
        }

        ArrayNode summaries = root.putArray("summaryBlocks");
        appendFixedSummary(
                summaries,
                root.path("teamOverview"),
                "TEAM",
                null,
                "TEAM_OVERVIEW"
        );
        JsonNode employeeHeadlines = root.path("employeeHeadlines");
        for (String employeeRef : input.manifest().employeeRefs()) {
            appendFixedSummary(
                    summaries,
                    employeeHeadlines.path(employeeRef),
                    "EMPLOYEE",
                    employeeRef,
                    "HEADLINE"
            );
        }
        for (JsonNode summary : root.path("supportingSummaries")) {
            summaries.add(summary.deepCopy());
        }

        root.remove("teamOverview");
        root.remove("employeeHeadlines");
        root.remove("supportingSummaries");
    }

    private void appendFixedSummary(
            ArrayNode summaries,
            JsonNode source,
            String scope,
            String employeeRef,
            String section
    ) {
        if (!source.isObject()) {
            return;
        }
        ObjectNode summary = summaries.addObject();
        summary.put("scope", scope);
        nullable(summary, "employeeRef", employeeRef);
        summary.put("section", section);
        summary.putNull("categoryCode");
        summary.set("text", source.path("text").deepCopy());
        summary.set(
                "evidenceRefs",
                source.path("evidenceRefs").deepCopy()
        );
    }

    @Override
    protected void validateVersionSpecificFields(
            JsonNode root,
            WeeklyInterpretationInput input,
            ValidationContext context
    ) {
        List<CandidateSignal> storeCandidates = WeeklyPrimarySignalPolicy.orderedStoreCandidates(input);
        JsonNode primarySignal = root.path("primarySignal");
        if (storeCandidates.isEmpty()) {
            if (!primarySignal.isNull()) {
                context.add(
                        "PRIMARY_SIGNAL_NOT_ALLOWED",
                        "$.primarySignal",
                        nullableText(primarySignal.path("candidateRef"))
                );
            }
            validateSecondaryInsights(root, null, context);
            return;
        }
        if (!primarySignal.isObject()) {
            context.add(
                    "PRIMARY_SIGNAL_REQUIRED",
                    "$.primarySignal",
                    storeCandidates.get(0).candidateRef()
            );
            validateSecondaryInsights(root, null, context);
            return;
        }

        String candidateRef = nullableText(primarySignal.path("candidateRef"));
        String expectedRef = storeCandidates.get(0).candidateRef();
        if (!expectedRef.equals(candidateRef)) {
            context.add(
                    "PRIMARY_SIGNAL_CANDIDATE_MISMATCH",
                    "$.primarySignal.candidateRef",
                    expectedRef
            );
        }
        String employeeRef = validateScope(
                primarySignal,
                "$.primarySignal",
                context
        );
        validateCategory(primarySignal, "$.primarySignal", context);
        validateCandidate(primarySignal, "$.primarySignal", context);
        validateEvidence(
                primarySignal.path("evidenceRefs"),
                "$.primarySignal.evidenceRefs",
                employeeRef,
                context
        );
        validateSecondaryInsights(root, candidateRef, context);
    }

    private void validateSecondaryInsights(
            JsonNode root,
            String primaryCandidateRef,
            ValidationContext context
    ) {
        for (int index = 0; index < root.path("insights").size(); index++) {
            JsonNode insight = root.path("insights").get(index);
            String secondaryRef = nullableText(insight.path("candidateRef"));
            if (secondaryRef == null) {
                context.add(
                        "SECONDARY_INSIGHT_CANDIDATE_REQUIRED",
                        "$.insights[" + index + "].candidateRef",
                        null
                );
            }
            if (primaryCandidateRef != null
                    && primaryCandidateRef.equals(secondaryRef)) {
                context.add(
                        "DUPLICATE_PRIMARY_CANDIDATE_REF",
                        "$.insights[" + index + "].candidateRef",
                        primaryCandidateRef
                );
            }
        }
    }
    @Override
    protected void validateVersionSpecificNarrativeEvidenceDimensions(
            JsonNode root,
            ValidationContext context
    ) {
        if (!root.path("primarySignal").isObject()) {
            return;
        }
        var primarySignals = JsonNodeFactory.instance.arrayNode();
        primarySignals.add(root.path("primarySignal"));
        validateNarrativeEvidenceDimensions(
                primarySignals,
                List.of("text"),
                "$.primarySignal",
                context
        );
    }

    @Override
    protected boolean requiresStoreHeadline() {
        return false;
    }

    private Map<String, EvidenceIndexEntry> evidence(
            WeeklyInterpretationInput input
    ) {
        Map<String, EvidenceIndexEntry> result = new HashMap<>();
        input.manifest().evidence().forEach(value ->
                result.put(value.evidenceRef(), value)
        );
        return result;
    }

    private String candidateScope(
            CandidateSignal candidate,
            Map<String, EvidenceIndexEntry> evidence
    ) {
        if (candidate.employeeRef() != null) {
            return "EMPLOYEE";
        }
        boolean teamOnly = candidate.evidenceRefs().stream()
                .map(evidence::get)
                .allMatch(value -> value != null
                        && value.scope() == WeeklyInterpretationInput.Scope.TEAM);
        return teamOnly ? "TEAM" : "STORE";
    }

    private void putNullIfMissing(ObjectNode node, String field) {
        if (!node.has(field)) {
            node.putNull(field);
        }
    }

    private void nullable(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }
}
