package com.storeanalytics.interpretation.generation;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.contract.LlmContractResources;
import com.storeanalytics.interpretation.contract.WeeklyCandidateNarrativePolicy;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput;
import com.storeanalytics.interpretation.contract.WeeklyPrimarySignalPolicy;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Period;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Snapshot;
import com.storeanalytics.interpretation.snapshot.PersistedWeeklySnapshot;
import com.storeanalytics.interpretation.snapshot.WeeklySnapshotStore;
import com.storeanalytics.interpretation.validation.LlmJsonSchemaValidator;
import com.storeanalytics.interpretation.validation.StructuralValidationViolation;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Component
public class LlmProviderRequestFactory {

    private static final String STORE_REF = "S01";
    private static final Set<String> RELATIONSHIP_THEMES = Set.of(
            "COMPETENCY_LEADER",
            "MOST_IMPROVED",
            "LEARNING_OPPORTUNITY"
    );

    private final WeeklySnapshotStore snapshotStore;
    private final LlmValidationRetryPromptFactory retryPromptFactory;
    private final LlmProviderInputCompactor inputCompactor;
    private final ObjectMapper objectMapper;
    private final ObjectWriter canonicalWriter;
    private final LlmJsonSchemaValidator inputValidator;
    private final Map<String, String> systemPrompts;
    private final Map<Integer, String> responseSchemas;

    public LlmProviderRequestFactory(
            WeeklySnapshotStore snapshotStore,
            LlmValidationRetryPromptFactory retryPromptFactory,
            LlmProviderInputCompactor inputCompactor
    ) {
        this.snapshotStore = snapshotStore;
        this.retryPromptFactory = retryPromptFactory;
        this.inputCompactor = inputCompactor;
        this.objectMapper = JsonMapper.builder()
                .findAndAddModules()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build();
        this.canonicalWriter = objectMapper.writer();
        this.inputValidator = new LlmJsonSchemaValidator(
                LlmContractResources.INPUT_SCHEMA
        );
        this.systemPrompts = Map.ofEntries(
                Map.entry(
                        LlmContractResources.PROMPT_VERSION,
                        resource(LlmContractResources.SYSTEM_PROMPT)
                ),
                Map.entry(
                        LlmContractResources.NEXT_PROMPT_VERSION,
                        resource(LlmContractResources.NEXT_SYSTEM_PROMPT)
                ),
                Map.entry(
                        LlmContractResources.CONCISE_PROMPT_VERSION,
                        resource(LlmContractResources.CONCISE_SYSTEM_PROMPT)
                ),
                Map.entry(
                        LlmContractResources.REVISED_CONCISE_PROMPT_VERSION,
                        resource(
                                LlmContractResources.REVISED_CONCISE_SYSTEM_PROMPT
                        )
                ),
                Map.entry(
                        LlmContractResources.STRICT_CONCISE_PROMPT_VERSION,
                        resource(
                                LlmContractResources.STRICT_CONCISE_SYSTEM_PROMPT
                        )
                ),
                Map.entry(
                        LlmContractResources.ACTIONABLE_CONCISE_PROMPT_VERSION,
                        resource(
                                LlmContractResources
                                        .ACTIONABLE_CONCISE_SYSTEM_PROMPT
                        )
                ),
                Map.entry(
                        LlmContractResources.EVIDENCE_GUARDED_PROMPT_VERSION,
                        resource(
                                LlmContractResources
                                        .EVIDENCE_GUARDED_SYSTEM_PROMPT
                        )
                ),
                Map.entry(
                        LlmContractResources.HARDENED_EVIDENCE_PROMPT_VERSION,
                        resource(
                                LlmContractResources
                                        .HARDENED_EVIDENCE_SYSTEM_PROMPT
                        )
                ),
                Map.entry(
                        LlmContractResources.NARRATIVE_GUARDED_PROMPT_VERSION,
                        resource(
                                LlmContractResources
                                        .NARRATIVE_GUARDED_SYSTEM_PROMPT
                        )
                ),
                Map.entry(
                        LlmContractResources
                                .CAUSAL_NARRATIVE_GUARDED_PROMPT_VERSION,
                        resource(
                                LlmContractResources
                                        .CAUSAL_NARRATIVE_GUARDED_SYSTEM_PROMPT
                        )
                ),
                Map.entry(
                        LlmContractResources.PRIMARY_SIGNAL_PROMPT_VERSION,
                        resource(
                                LlmContractResources.PRIMARY_SIGNAL_SYSTEM_PROMPT
                        )
                ),
                Map.entry(
                        LlmContractResources.STRUCTURED_SUMMARY_PROMPT_VERSION,
                        resource(
                                LlmContractResources
                                        .STRUCTURED_SUMMARY_SYSTEM_PROMPT
                        )
                ),
                Map.entry(
                        LlmContractResources
                                .TEAM_GUARDED_STRUCTURED_SUMMARY_PROMPT_VERSION,
                        resource(
                                LlmContractResources
                                        .TEAM_GUARDED_STRUCTURED_SUMMARY_SYSTEM_PROMPT
                        )
                ),
                Map.entry(
                        LlmContractResources
                                .MATRIX_HARDENED_STRUCTURED_SUMMARY_PROMPT_VERSION,
                        resource(
                                LlmContractResources
                                        .MATRIX_HARDENED_STRUCTURED_SUMMARY_SYSTEM_PROMPT
                        )
                ),
                Map.entry(
                        LlmContractResources
                                .PRODUCTION_HARDENED_STRUCTURED_SUMMARY_PROMPT_VERSION,
                        resource(
                                LlmContractResources
                                        .PRODUCTION_HARDENED_STRUCTURED_SUMMARY_SYSTEM_PROMPT
                        )
                ),
                Map.entry(
                        LlmContractResources.DETERMINISTIC_NARRATIVE_PROMPT_VERSION,
                        resource(
                                LlmContractResources
                                        .DETERMINISTIC_NARRATIVE_SYSTEM_PROMPT
                        )
                ),
                Map.entry(
                        LlmContractResources.PRIVACY_REDUCED_PROMPT_VERSION,
                        resource(
                                LlmContractResources
                                        .PRIVACY_REDUCED_SYSTEM_PROMPT
                        )
                ),
                promptEntry(LlmContractResources
                        .MODERATION_SAFE_PRIVACY_REDUCED_PROMPT_VERSION),
                promptEntry(LlmContractResources
                        .BOUNDED_PRIVACY_REDUCED_PROMPT_VERSION)
        );
        this.responseSchemas = Map.of(
                LlmContractResources.CONTENT_SCHEMA_VERSION,
                minifiedJsonResource(LlmContractResources.CONTENT_SCHEMA),
                LlmContractResources.NEXT_CONTENT_SCHEMA_VERSION,
                minifiedJsonResource(
                        LlmContractResources.NEXT_CONTENT_SCHEMA
                ),
                LlmContractResources.PRIMARY_SIGNAL_CONTENT_SCHEMA_VERSION,
                minifiedJsonResource(
                        LlmContractResources.PRIMARY_SIGNAL_CONTENT_SCHEMA
                )
        );
    }

    private Map.Entry<String, String> promptEntry(String promptVersion) {
        return Map.entry(
                promptVersion,
                resource(LlmContractResources.systemPrompt(promptVersion))
        );
    }

    public PreparedLlmProviderRequest prepare(
            LlmAnalysisJob job,
            Instant now,
            Duration callTimeout
    ) {
        LlmAnalysisJob value = requireNonNull(job, "job");
        Instant timestamp = requireNonNull(now, "now");
        Duration timeout = requireNonNull(callTimeout, "callTimeout");
        require(!timeout.isZero() && !timeout.isNegative(),
                "callTimeout must be positive");
        require(LlmContractResources.isSupportedPair(
                        value.promptVersion(),
                        value.contentSchemaVersion()
                ),
                "LLM job prompt and content schema versions are not a packaged pair");
        String systemPrompt = systemPrompts.get(value.promptVersion());
        String responseSchema = responseSchemas.get(value.contentSchemaVersion());
        PersistedWeeklySnapshot snapshot = snapshotStore.findById(value.snapshotId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "LLM job snapshot does not exist: " + value.snapshotId()
                ));
        boolean privacyReduced = LlmContractResources
                .isPrivacyReducedPrompt(value.promptVersion());
        WeeklyInterpretationInput input = inputCompactor.compact(
                input(snapshot),
                privacyReduced,
                LlmContractResources.isBoundedPrivacyReducedPrompt(
                        value.promptVersion()
                )
        );
        String inputJson = serialize(input, "LLM input");
        List<StructuralValidationViolation> violations = inputValidator.validate(inputJson);
        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "Persisted LLM input violates packaged schema: "
                            + violations.size() + " violation(s)"
            );
        }
        GenerationParameters parameters = generationParameters(
                value.generationParameters()
        );
        Instant timeoutDeadline = timestamp.plus(timeout);
        Instant callDeadline = timeoutDeadline.isBefore(value.deadlineAt())
                ? timeoutDeadline : value.deadlineAt();
        require(callDeadline.isAfter(timestamp), "LLM job deadline has passed");
        LlmProviderRequest request = new LlmProviderRequest(
                value.id(),
                value.providerCode(),
                value.requestedModel(),
                retryPromptFactory.appendRetryInstruction(systemPrompt, value),
                inputJson,
                specializedResponseSchema(
                        input,
                        responseSchema,
                        value.contentSchemaVersion(),
                        value.promptVersion()
                ),
                parameters.temperature(),
                parameters.maxOutputTokens(),
                callDeadline
        );
        return new PreparedLlmProviderRequest(request, hash(request));
    }

    private WeeklyInterpretationInput input(PersistedWeeklySnapshot snapshot) {
        Snapshot header = new Snapshot(
                snapshot.id(),
                snapshot.revision(),
                snapshot.factsHash(),
                STORE_REF,
                snapshot.timezone(),
                new Period(
                        snapshot.query().period().start(),
                        snapshot.query().period().end()
                ),
                new Period(
                        snapshot.query().comparisonPeriod().start(),
                        snapshot.query().comparisonPeriod().end()
                ),
                snapshot.qualityStatus(),
                snapshot.versions()
        );
        return new WeeklyInterpretationInput(
                snapshot.payload().contractVersion(),
                header,
                snapshot.payload().manifest(),
                snapshot.payload().facts()
        );
    }

    private String specializedResponseSchema(
            WeeklyInterpretationInput input,
            String responseSchema,
            int contentSchemaVersion,
            String promptVersion
    ) {
        try {
            ObjectNode schema = (ObjectNode) objectMapper.readTree(responseSchema);
            boolean hasEvidence = !input.facts().store().isEmpty()
                    || !input.facts().team().isEmpty()
                    || input.facts().employees().stream()
                    .anyMatch(employee -> !employee.facts().isEmpty());
            require(hasEvidence, "LLM input has no available evidence");
            inlineLocalReferences(schema.path("properties"), schema);
            schema.remove("$defs");
            boolean flatOutput = contentSchemaVersion
                    == LlmContractResources.NEXT_CONTENT_SCHEMA_VERSION
                    || contentSchemaVersion
                    == LlmContractResources.PRIMARY_SIGNAL_CONTENT_SCHEMA_VERSION;
            if (!flatOutput) {
                collapseNullableUnions(schema);
            }
            inferPrimitiveTypesFromEnums(schema);
            stripProviderOnlyMetadata(schema, flatOutput);
            int employeeCount = input.manifest().employeeRefs().size();
            ObjectNode employees = (ObjectNode) schema.at("/properties/employees");
            employees.put("minItems", employeeCount);
            employees.put("maxItems", employeeCount);
            if (flatOutput) {
                constrainFlatProviderOutput(
                        schema,
                        employeeCount,
                        LlmContractResources.isConcisePrompt(promptVersion),
                        relationshipCandidateCount(input),
                        relationshipCandidateThemes(input),
                        contentSchemaVersion
                );
                if (LlmContractResources.isStructuredSummaryPrompt(
                        promptVersion
                )) {
                    constrainStructuredSummaryTransport(schema, input);
                }
                constrainProviderReferences(schema, input);
                boolean teamGuarded = LlmContractResources
                        .TEAM_GUARDED_STRUCTURED_SUMMARY_PROMPT_VERSION
                        .equals(promptVersion);
                boolean privacyReduced = LlmContractResources
                        .isPrivacyReducedPrompt(promptVersion);
                boolean deterministicNarrative = privacyReduced
                        || LlmContractResources
                        .DETERMINISTIC_NARRATIVE_PROMPT_VERSION
                        .equals(promptVersion);
                boolean productionHardened = deterministicNarrative
                        || LlmContractResources
                        .PRODUCTION_HARDENED_STRUCTURED_SUMMARY_PROMPT_VERSION
                        .equals(promptVersion);
                boolean matrixHardened = productionHardened
                        || LlmContractResources
                        .MATRIX_HARDENED_STRUCTURED_SUMMARY_PROMPT_VERSION
                        .equals(promptVersion);
                if (teamGuarded || matrixHardened) {
                    constrainTeamOverviewEvidence(schema, input);
                }
                if (matrixHardened) {
                    constrainMatrixHardenedTransport(schema, input);
                }
                constrainPrimarySignal(schema, input, contentSchemaVersion);
                if (LlmContractResources.isStrictConcisePrompt(promptVersion)) {
                    constrainStrictInsights(schema, input, contentSchemaVersion);
                }
                if (productionHardened) {
                    constrainProductionHardenedTransport(schema, input);
                }
                if (deterministicNarrative) {
                    constrainDeterministicCandidateNarratives(schema, input);
                }
                if (privacyReduced) {
                    constrainPrivacyReducedTransport(schema);
                }
                if (LlmContractResources.isEvidenceGuardedPrompt(promptVersion)) {
                    constrainCandidateBoundedActions(schema, input);
                }
            } else {
                constrainProviderOutput(schema, null);
                pruneNestedProviderSchemas(schema, 0);
            }
            return canonicalWriter.writeValueAsString(schema);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Cannot specialize LLM response schema", exception
            );
        }
    }

    private void inlineLocalReferences(JsonNode node, ObjectNode root) {
        if (node instanceof ObjectNode object) {
            JsonNode ref = object.get("$ref");
            if (ref != null) {
                require(ref.isTextual(), "Provider schema reference must be textual");
                String reference = ref.asText();
                require(reference.startsWith("#/"),
                        "Provider schema reference must be local");
                JsonNode target = root.at(reference.substring(1));
                require(target instanceof ObjectNode,
                        "Provider schema reference target must be an object");
                ObjectNode siblings = object.deepCopy();
                siblings.remove("$ref");
                ObjectNode resolved = ((ObjectNode) target).deepCopy();
                object.removeAll();
                object.setAll(resolved);
                object.setAll(siblings);
            }
        }
        node.forEach(child -> inlineLocalReferences(child, root));
    }

    private void collapseNullableUnions(JsonNode node) {
        if (node instanceof ObjectNode object && object.has("anyOf")) {
            collapseProviderUnion(object);
        }
        node.forEach(this::collapseNullableUnions);
    }

    private void collapseProviderUnion(ObjectNode object) {
        JsonNode concrete = null;
        for (JsonNode alternative : object.path("anyOf")) {
            if (!"null".equals(alternative.path("type").asText())) {
                concrete = alternative;
                break;
            }
        }
        require(concrete instanceof ObjectNode,
                "Provider schema union has no concrete object");
        ObjectNode siblings = object.deepCopy();
        siblings.remove("anyOf");
        object.removeAll();
        object.setAll(((ObjectNode) concrete).deepCopy());
        object.setAll(siblings);
    }

    private void removeRequiredProperty(ObjectNode owner, String propertyName) {
        if (!(owner.path("required") instanceof ArrayNode required)) {
            return;
        }
        for (int index = required.size() - 1; index >= 0; index--) {
            if (propertyName.equals(required.get(index).asText())) {
                required.remove(index);
            }
        }
    }

    private void inferPrimitiveTypesFromEnums(JsonNode node) {
        if (node instanceof ObjectNode object
                && !object.has("type")
                && object.path("enum").isArray()
                && !object.path("enum").isEmpty()) {
            JsonNode first = object.path("enum").get(0);
            if (first.isTextual()) {
                object.put("type", "string");
            } else if (first.isIntegralNumber()) {
                object.put("type", "integer");
            } else if (first.isNumber()) {
                object.put("type", "number");
            } else if (first.isBoolean()) {
                object.put("type", "boolean");
            }
        }
        node.forEach(this::inferPrimitiveTypesFromEnums);
    }

    private void pruneNestedProviderSchemas(JsonNode node, int objectDepth) {
        if (!(node instanceof ObjectNode object)) {
            return;
        }
        if ("object".equals(object.path("type").asText())) {
            if (objectDepth >= 2) {
                String shape = shapeSignature(object);
                object.removeAll();
                object.put("type", "object");
                object.put("description", "Required JSON shape: " + shape);
                return;
            }
            object.path("properties").forEach(
                    child -> pruneNestedProviderSchemas(child, objectDepth + 1)
            );
            return;
        }
        if ("array".equals(object.path("type").asText())) {
            pruneNestedProviderSchemas(object.path("items"), objectDepth);
        }
    }

    private String shapeSignature(JsonNode node) {
        String type = node.path("type").asText();
        if ("object".equals(type)) {
            List<String> fields = new ArrayList<>();
            node.path("properties").properties().forEach(entry ->
                    fields.add(entry.getKey() + ":" + shapeSignature(entry.getValue()))
            );
            return "{" + String.join(",", fields) + "}";
        }
        if ("array".equals(type)) {
            String bound = node.has("maxItems")
                    ? "(maxItems=" + node.path("maxItems").asInt() + ")"
                    : "";
            return "[" + shapeSignature(node.path("items")) + "]" + bound;
        }
        if ("string".equals(type) && node.has("maxLength")) {
            return type + "(maxLength=" + node.path("maxLength").asInt() + ")";
        }
        return type.isBlank() ? "value" : type;
    }

    private void constrainFlatProviderOutput(
            ObjectNode schema,
            int employeeCount,
            boolean concise,
            int relationshipCandidateCount,
            List<String> relationshipCandidateThemes,
            int contentSchemaVersion
    ) {
        boolean primarySignal = contentSchemaVersion
                == LlmContractResources.PRIMARY_SIGNAL_CONTENT_SCHEMA_VERSION;
        int requiredSummaries = primarySignal ? 1 + employeeCount
                : 2 + employeeCount;
        requireMinimumCollectionSize(
                schema.at("/properties/summaryBlocks"),
                concise ? requiredSummaries
                        : requiredSummaries + employeeCount
        );
        if (concise) {
            boundCollection(
                    schema.at("/properties/summaryBlocks"),
                    Math.max(6, 2 + employeeCount * 3)
            );
            boundCollection(
                    schema.at("/properties/insights"),
                    Math.max(5, 3 + employeeCount * 2)
            );
            boundCollection(
                    schema.at("/properties/actions"),
                    Math.max(3, 2 + (employeeCount + 1) / 2)
            );
        } else {
            boundCollection(
                    schema.at("/properties/summaryBlocks"),
                    Math.max(8, 2 + employeeCount * 6)
            );
            boundCollection(
                    schema.at("/properties/insights"),
                    Math.max(6, 4 + employeeCount * 3)
            );
            boundCollection(
                    schema.at("/properties/actions"),
                    Math.max(4, 3 + employeeCount)
            );
        }
        boundCollection(
                schema.at("/properties/teamRelationships"),
                Math.min(
                        Math.max(4, Math.min(8, 2 + employeeCount / 2)),
                        relationshipCandidateCount
                )
        );
        if (!relationshipCandidateThemes.isEmpty()) {
            setStringEnum(
                    schema.at(
                            "/properties/teamRelationships/items/properties/type"
                    ),
                    relationshipCandidateThemes
            );
        }
        removeProviderOwnedProperty(schema, "dataLimitations");
    }

    private void constrainStructuredSummaryTransport(
            ObjectNode schema,
            WeeklyInterpretationInput input
    ) {
        ObjectNode properties = (ObjectNode) schema.path("properties");
        ObjectNode summaryCollection = (ObjectNode) properties.path(
                "summaryBlocks"
        );
        ObjectNode summaryItem = (ObjectNode) summaryCollection.path(
                "items"
        );
        int mandatorySummaryCount =
                1 + input.manifest().employeeRefs().size();
        int supportingSummaryLimit = Math.max(
                0,
                summaryCollection.path("maxItems").asInt(mandatorySummaryCount)
                        - mandatorySummaryCount
        );

        ObjectNode teamOverview = summaryNarrativeSchema(summaryItem);
        ObjectNode employeeHeadlines = objectMapper.createObjectNode();
        employeeHeadlines.put("type", "object");
        employeeHeadlines.put("additionalProperties", false);
        ArrayNode headlineRequired = employeeHeadlines.putArray("required");
        ObjectNode headlineProperties =
                employeeHeadlines.putObject("properties");
        for (String employeeRef : input.manifest().employeeRefs()) {
            headlineRequired.add(employeeRef);
            headlineProperties.set(
                    employeeRef,
                    summaryNarrativeSchema(summaryItem)
            );
        }

        ObjectNode supportingSummaries = objectMapper.createObjectNode();
        supportingSummaries.put("type", "array");
        supportingSummaries.put("minItems", 0);
        supportingSummaries.put("maxItems", supportingSummaryLimit);
        ObjectNode supportingItem = summaryItem.deepCopy();
        setStringEnum(
                supportingItem.at("/properties/section"),
                List.of(
                        "WORKLOAD",
                        "RESULT",
                        "DYNAMICS",
                        "CATEGORY_PERFORMANCE",
                        "ADDITIONAL_SALES",
                        "PLAN_OUTLOOK"
                )
        );
        supportingSummaries.set("items", supportingItem);

        removeProviderOwnedProperty(schema, "employees");
        removeProviderOwnedProperty(schema, "summaryBlocks");
        properties.set("teamOverview", teamOverview);
        properties.set("employeeHeadlines", employeeHeadlines);
        properties.set("supportingSummaries", supportingSummaries);
        addRequiredProperty(schema, "teamOverview");
        addRequiredProperty(schema, "employeeHeadlines");
        addRequiredProperty(schema, "supportingSummaries");
    }

    private void constrainTeamOverviewEvidence(
            ObjectNode schema,
            WeeklyInterpretationInput input
    ) {
        List<String> teamEvidenceRefs = input.facts().team().stream()
                .map(WeeklyInterpretationInput.Fact::evidenceRef)
                .sorted()
                .toList();
        require(
                !teamEvidenceRefs.isEmpty(),
                "Structured team overview requires TEAM evidence"
        );
        setStringEnum(
                schema.at(
                        "/properties/teamOverview/properties/"
                                + "evidenceRefs/items"
                ),
                teamEvidenceRefs
        );
    }

    private void constrainMatrixHardenedTransport(
            ObjectNode schema,
            WeeklyInterpretationInput input
    ) {
        for (WeeklyInterpretationInput.EmployeeFacts employee
                : input.facts().employees()) {
            JsonNode headline = schema.at(
                    "/properties/employeeHeadlines/properties/"
                            + employee.employeeRef()
            );
            List<String> employeeEvidence = employee.facts().stream()
                    .map(WeeklyInterpretationInput.Fact::evidenceRef)
                    .sorted()
                    .toList();
            require(
                    !employeeEvidence.isEmpty(),
                    "Employee headline requires employee evidence"
            );
            setStringEnum(
                    headline.at("/properties/evidenceRefs/items"),
                    employeeEvidence
            );
            if (employee.analysisStatus()
                    == WeeklyInterpretationInput.Sufficiency.INSUFFICIENT) {
                setRequiredStringEnum(
                        headline.at("/properties/text"),
                        List.of(
                                "Данных недостаточно для персонального "
                                        + "анализа сотрудника."
                        )
                );
            }
        }

        boundCollection(schema.at("/properties/actions"), 1);
        constrainMatrixHardenedRelationships(schema, input);
    }

    private void constrainProductionHardenedTransport(
            ObjectNode schema,
            WeeklyInterpretationInput input
    ) {
        setRequiredStringEnum(
                schema.at("/properties/teamOverview/properties/text"),
                List.of(teamOverviewText(input))
        );
        if (schema.at("/properties/teamRelationships")
                instanceof ObjectNode relationships) {
            relationships.put("minItems", 0);
            relationships.put("maxItems", 0);
        }
    }

    private void constrainPrivacyReducedTransport(ObjectNode schema) {
        removeProviderOwnedProperty(schema, "employeeHeadlines");
        ObjectNode marker = objectMapper.createObjectNode();
        marker.put("type", "boolean");
        marker.putArray("enum").add(true);
        ((ObjectNode) schema.path("properties")).set(
                "backendEmployeeHeadlines", marker
        );
        addRequiredProperty(schema, "backendEmployeeHeadlines");
    }

    private void constrainDeterministicCandidateNarratives(
            ObjectNode schema,
            WeeklyInterpretationInput input
    ) {
        List<WeeklyInterpretationInput.CandidateSignal> primaryCandidates =
                WeeklyPrimarySignalPolicy.orderedStoreCandidates(input);
        String primaryRef = null;
        if (!primaryCandidates.isEmpty()) {
            WeeklyInterpretationInput.CandidateSignal primary =
                    primaryCandidates.get(0);
            primaryRef = primary.candidateRef();
            setRequiredStringEnum(
                    concreteNullableAlternative(
                            schema.at("/properties/primarySignal")
                    ).at("/properties/text"),
                    List.of(WeeklyCandidateNarrativePolicy
                            .forCandidate(primary).summary())
            );
        }

        String consumedRef = primaryRef;
        List<WeeklyInterpretationInput.CandidateSignal> secondary =
                nonRelationshipCandidates(input).stream()
                        .filter(candidate -> !candidate.candidateRef().equals(
                                consumedRef
                        ))
                        .toList();
        if (secondary.isEmpty()) {
            return;
        }
        JsonNode insight = schema.at("/properties/insights/items");
        setRequiredStringEnum(
                insight.at("/properties/title"),
                secondary.stream()
                        .map(WeeklyCandidateNarrativePolicy::forCandidate)
                        .map(WeeklyCandidateNarrativePolicy.Narrative::title)
                        .distinct()
                        .sorted()
                        .toList()
        );
        setRequiredStringEnum(
                insight.at("/properties/summary"),
                secondary.stream()
                        .map(WeeklyCandidateNarrativePolicy::forCandidate)
                        .map(WeeklyCandidateNarrativePolicy.Narrative::summary)
                        .distinct()
                        .sorted()
                        .toList()
        );
    }

    private String teamOverviewText(WeeklyInterpretationInput input) {
        boolean comparable = input.facts().team().stream()
                .filter(fact -> "RATING_ELIGIBLE_COUNT".equals(
                        fact.metricCode()
                ))
                .map(WeeklyInterpretationInput.Fact::value)
                .map(Object::toString)
                .map(BigDecimal::new)
                .anyMatch(value -> value.compareTo(
                        new BigDecimal("2")
                ) >= 0);
        return comparable
                ? "Командные данные позволяют сопоставить сотрудников."
                : "Сопоставление сотрудников ограничено недостаточной "
                        + "командной базой.";
    }

    private void constrainMatrixHardenedRelationships(
            ObjectNode schema,
            WeeklyInterpretationInput input
    ) {
        List<WeeklyInterpretationInput.CandidateSignal> candidates =
                input.facts().candidateSignals().stream()
                        .filter(candidate -> RELATIONSHIP_THEMES.contains(
                                candidate.theme()
                        ))
                        .toList();
        JsonNode collection = schema.at("/properties/teamRelationships");
        if (!(collection instanceof ObjectNode relationships)) {
            return;
        }
        relationships.put("minItems", candidates.size());
        relationships.put("maxItems", candidates.size());
        if (candidates.isEmpty()) {
            return;
        }

        ObjectNode item = (ObjectNode) relationships.path("items");
        setRequiredStringEnum(
                item.at("/properties/summary"),
                candidates.stream()
                        .map(candidate -> relationshipSummary(
                                candidate.theme()
                        ))
                        .distinct()
                        .sorted()
                        .toList()
        );
        if (candidates.size() != 1) {
            return;
        }

        WeeklyInterpretationInput.CandidateSignal candidate =
                candidates.get(0);
        setRequiredStringEnum(
                item.at("/properties/type"),
                List.of(candidate.theme())
        );
        if (candidate.competencyCode() == null) {
            setNullOnly(item.at("/properties/competencyCode"));
        } else {
            setRequiredStringEnum(
                    item.at("/properties/competencyCode"),
                    List.of(candidate.competencyCode())
            );
        }
        constrainExactStringArray(
                item.at("/properties/sourceEmployeeRefs"),
                candidate.employeeRef() == null
                        ? List.of() : List.of(candidate.employeeRef())
        );
        constrainExactStringArray(
                item.at("/properties/targetEmployeeRefs"),
                candidate.targetEmployeeRefs()
        );
        constrainExactStringArray(
                item.at("/properties/evidenceRefs"),
                candidate.evidenceRefs()
        );
    }

    private void constrainExactStringArray(
            JsonNode node,
            List<String> values
    ) {
        if (!(node instanceof ObjectNode collection)) {
            return;
        }
        collection.put("minItems", values.size());
        collection.put("maxItems", values.size());
        if (!values.isEmpty()) {
            setStringEnum(collection.path("items"), values);
        }
    }

    private String relationshipSummary(String theme) {
        return switch (theme) {
            case "COMPETENCY_LEADER" ->
                    "В команде подтверждён лидер по соответствующей "
                            + "компетенции.";
            case "MOST_IMPROVED" ->
                    "Подтверждена наиболее заметная положительная динамика "
                            + "среди сопоставимых сотрудников.";
            case "LEARNING_OPPORTUNITY" ->
                    "Подтверждена возможность обмена практикой по "
                            + "соответствующей компетенции.";
            default -> throw new IllegalArgumentException(
                    "Unsupported relationship theme: " + theme
            );
        };
    }

    private ObjectNode summaryNarrativeSchema(ObjectNode summaryItem) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("type", "object");
        result.put("additionalProperties", false);
        ArrayNode required = result.putArray("required");
        required.add("text");
        required.add("evidenceRefs");
        ObjectNode properties = result.putObject("properties");
        properties.set(
                "text",
                summaryItem.at("/properties/text").deepCopy()
        );
        properties.set(
                "evidenceRefs",
                summaryItem.at("/properties/evidenceRefs").deepCopy()
        );
        return result;
    }

    private void addRequiredProperty(
            ObjectNode owner,
            String propertyName
    ) {
        ArrayNode required = (ArrayNode) owner.path("required");
        for (JsonNode value : required) {
            if (propertyName.equals(value.asText())) {
                return;
            }
        }
        required.add(propertyName);
    }

    private void constrainPrimarySignal(
            ObjectNode schema,
            WeeklyInterpretationInput input,
            int contentSchemaVersion
    ) {
        if (contentSchemaVersion
                != LlmContractResources.PRIMARY_SIGNAL_CONTENT_SCHEMA_VERSION) {
            return;
        }
        List<WeeklyInterpretationInput.CandidateSignal> candidates =
                WeeklyPrimarySignalPolicy.orderedStoreCandidates(input);
        JsonNode primarySignal = schema.at("/properties/primarySignal");
        if (candidates.isEmpty()) {
            setNullOnly(primarySignal);
            return;
        }
        JsonNode item = concreteNullableAlternative(primarySignal);
        WeeklyInterpretationInput.CandidateSignal primary = candidates.get(0);
        setRequiredStringEnum(
                item.at("/properties/candidateRef"),
                List.of(primary.candidateRef())
        );
        setStringEnum(
                item.at("/properties/kind"),
                List.of(primary.kind().name())
        );
        setStringEnum(
                item.at("/properties/theme"),
                List.of(primary.theme())
        );
    }

    private void constrainStrictInsights(
            ObjectNode schema,
            WeeklyInterpretationInput input,
            int contentSchemaVersion
    ) {
        List<WeeklyInterpretationInput.CandidateSignal> candidates =
                nonRelationshipCandidates(input);
        if (contentSchemaVersion
                == LlmContractResources.PRIMARY_SIGNAL_CONTENT_SCHEMA_VERSION) {
            List<WeeklyInterpretationInput.CandidateSignal> primaryCandidates =
                    WeeklyPrimarySignalPolicy.orderedStoreCandidates(input);
            if (!primaryCandidates.isEmpty()) {
                String primaryRef = primaryCandidates.get(0).candidateRef();
                candidates = candidates.stream()
                        .filter(candidate -> !primaryRef.equals(
                                candidate.candidateRef()
                        ))
                        .toList();
            }
        }
        ObjectNode insights = (ObjectNode) schema.at("/properties/insights");
        boundCollection(insights, candidates.size());
        if (candidates.isEmpty()) {
            return;
        }
        ObjectNode item = (ObjectNode) insights.path("items");
        setRequiredStringEnum(
                item.at("/properties/candidateRef"),
                candidates.stream()
                        .map(WeeklyInterpretationInput.CandidateSignal::candidateRef)
                        .sorted()
                        .toList()
        );
        setStringEnum(
                item.at("/properties/kind"),
                candidates.stream()
                        .map(candidate -> candidate.kind().name())
                        .distinct()
                        .sorted()
                        .toList()
        );
        setStringEnum(
                item.at("/properties/theme"),
                candidates.stream()
                        .map(WeeklyInterpretationInput.CandidateSignal::theme)
                        .distinct()
                        .sorted()
                        .toList()
        );
    }

    private void constrainCandidateBoundedActions(
            ObjectNode schema,
            WeeklyInterpretationInput input
    ) {
        boundCollection(
                schema.at("/properties/actions"),
                nonRelationshipCandidates(input).size()
        );
    }

    private List<WeeklyInterpretationInput.CandidateSignal>
            nonRelationshipCandidates(WeeklyInterpretationInput input) {
        return input.facts().candidateSignals().stream()
                .filter(candidate -> !RELATIONSHIP_THEMES.contains(
                        candidate.theme()
                ))
                .toList();
    }

    private int relationshipCandidateCount(
            WeeklyInterpretationInput input
    ) {
        return (int) input.facts().candidateSignals().stream()
                .map(WeeklyInterpretationInput.CandidateSignal::theme)
                .filter(RELATIONSHIP_THEMES::contains)
                .count();
    }

    private List<String> relationshipCandidateThemes(
            WeeklyInterpretationInput input
    ) {
        return input.facts().candidateSignals().stream()
                .map(WeeklyInterpretationInput.CandidateSignal::theme)
                .filter(RELATIONSHIP_THEMES::contains)
                .distinct()
                .sorted()
                .toList();
    }

    private void requireMinimumCollectionSize(JsonNode node, int minItems) {
        if (node instanceof ObjectNode collection) {
            int existing = collection.path("minItems").asInt(0);
            collection.put("minItems", Math.max(existing, minItems));
        }
    }

    private void boundCollection(JsonNode node, int maxItems) {
        if (node instanceof ObjectNode collection) {
            int existing = collection.path("maxItems").asInt(maxItems);
            collection.put("maxItems", Math.min(existing, maxItems));
        }
    }

    private void removeProviderOwnedProperty(
            ObjectNode owner,
            String propertyName
    ) {
        if (owner.path("properties") instanceof ObjectNode properties) {
            properties.remove(propertyName);
        }
        removeRequiredProperty(owner, propertyName);
    }

    private void constrainProviderReferences(
            JsonNode node,
            WeeklyInterpretationInput input
    ) {
        if (!(node instanceof ObjectNode object)) {
            return;
        }
        if (object.path("properties") instanceof ObjectNode properties) {
            List<Map.Entry<String, JsonNode>> fields = properties.properties()
                    .stream()
                    .toList();
            for (Map.Entry<String, JsonNode> field : fields) {
                List<String> allowed = providerReferenceValues(
                        field.getKey(),
                        input
                );
                if (allowed != null) {
                    boolean nullOnly = !isReferenceArray(field.getKey())
                            && "null".equals(
                                    field.getValue().path("type").asText()
                            );
                    if (nullOnly) {
                        continue;
                    }
                    if (allowed.isEmpty()) {
                        if (isReferenceArray(field.getKey())) {
                            setEmptyArray(field.getValue());
                        } else {
                            setNullOnly(field.getValue());
                        }
                        continue;
                    }
                    JsonNode target = isReferenceArray(field.getKey())
                            ? field.getValue().path("items")
                            : concreteNullableAlternative(field.getValue());
                    setStringEnum(target, allowed);
                }
                constrainProviderReferences(field.getValue(), input);
            }
        }
        constrainProviderReferences(object.path("items"), input);
        object.path("anyOf").forEach(child ->
                constrainProviderReferences(child, input)
        );
    }

    private boolean isReferenceArray(String propertyName) {
        return propertyName.endsWith("EmployeeRefs")
                || "employeeRefs".equals(propertyName)
                || "evidenceRefs".equals(propertyName);
    }

    private List<String> providerReferenceValues(
            String propertyName,
            WeeklyInterpretationInput input
    ) {
        if ("employeeRef".equals(propertyName)
                || propertyName.endsWith("EmployeeRefs")
                || "employeeRefs".equals(propertyName)) {
            return input.manifest().employeeRefs();
        }
        if ("categoryCode".equals(propertyName)) {
            return input.manifest().categoryCodes();
        }
        if ("evidenceRefs".equals(propertyName)) {
            return evidenceRefs(input);
        }
        if ("candidateRef".equals(propertyName)) {
            return input.manifest().candidateRefs();
        }
        if ("competencyCode".equals(propertyName)) {
            List<String> values = new ArrayList<>(
                    input.manifest().competencyCodes()
            );
            input.manifest().categoryCodes().stream()
                    .map(value -> "CATEGORY:" + value)
                    .forEach(values::add);
            return List.copyOf(values);
        }
        return null;
    }

    private List<String> evidenceRefs(WeeklyInterpretationInput input) {
        Set<String> values = new TreeSet<>();
        input.facts().store().stream()
                .map(WeeklyInterpretationInput.Fact::evidenceRef)
                .forEach(values::add);
        input.facts().team().stream()
                .map(WeeklyInterpretationInput.Fact::evidenceRef)
                .forEach(values::add);
        input.facts().employees().stream()
                .flatMap(employee -> employee.facts().stream())
                .map(WeeklyInterpretationInput.Fact::evidenceRef)
                .forEach(values::add);
        return List.copyOf(values);
    }

    private void setRequiredStringEnum(
            JsonNode node,
            List<String> values
    ) {
        if (node instanceof ObjectNode schema) {
            schema.removeAll();
            schema.put("type", "string");
            ArrayNode allowed = schema.putArray("enum");
            values.forEach(allowed::add);
        }
    }

    private void setStringEnum(JsonNode node, List<String> values) {
        if (!(node instanceof ObjectNode schema)) {
            return;
        }
        schema.put("type", "string");
        ArrayNode allowed = schema.putArray("enum");
        values.forEach(allowed::add);
    }

    private JsonNode concreteNullableAlternative(JsonNode node) {
        for (JsonNode alternative : node.path("anyOf")) {
            if (!"null".equals(alternative.path("type").asText())) {
                return alternative;
            }
        }
        return node;
    }

    private void setNullOnly(JsonNode node) {
        if (node instanceof ObjectNode schema) {
            schema.removeAll();
            schema.put("type", "null");
        }
    }

    private void setEmptyArray(JsonNode node) {
        if (node instanceof ObjectNode schema) {
            schema.put("type", "array");
            schema.put("minItems", 0);
            schema.put("maxItems", 0);
        }
    }

    private void constrainProviderOutput(JsonNode node, String propertyName) {
        if (!(node instanceof ObjectNode object)) {
            return;
        }
        String type = object.path("type").asText();
        if ("array".equals(type)) {
            boolean analyticalCollection = "object".equals(
                    object.path("items").path("type").asText()
            ) && !"employees".equals(propertyName)
                    && !"dataLimitations".equals(propertyName);
            if (analyticalCollection) {
                object.put("maxItems", 1);
            }
            constrainProviderOutput(object.path("items"), null);
            return;
        }
        if (!"object".equals(type)) {
            return;
        }
        object.path("properties").properties().forEach(entry -> {
            JsonNode child = entry.getValue();
            if (child instanceof ObjectNode childObject
                    && "string".equals(childObject.path("type").asText())) {
                int limit = "title".equals(entry.getKey()) ? 120 : 240;
                if ("title".equals(entry.getKey())
                        || "text".equals(entry.getKey())
                        || "summary".equals(entry.getKey())) {
                    childObject.put("maxLength", Math.min(
                            childObject.path("maxLength").asInt(limit), limit
                    ));
                }
            }
            constrainProviderOutput(child, entry.getKey());
        });
    }

    private void stripProviderOnlyMetadata(
            JsonNode node,
            boolean preserveNullableUnions
    ) {
        if (!(node instanceof ObjectNode object)) {
            return;
        }
        List<String> metadata = new ArrayList<>(List.of(
                    "$schema",
                    "$id",
                    "$comment",
                    "title",
                    "description",
                    "examples",
                    "default",
                    "uniqueItems",
                    "minimum",
                    "maximum",
                    "minLength",
                    "pattern",
                    "format"
            ));
        if (!preserveNullableUnions) {
            metadata.add("anyOf");
        }
        object.remove(metadata);
        object.path("properties").forEach(child ->
                stripProviderOnlyMetadata(child, preserveNullableUnions)
        );
        stripProviderOnlyMetadata(object.path("items"), preserveNullableUnions);
        object.path("anyOf").forEach(child ->
                stripProviderOnlyMetadata(child, preserveNullableUnions)
        );
    }

    private GenerationParameters generationParameters(String json) {
        try {
            GenerationParameters parameters = objectMapper.readValue(
                    json,
                    GenerationParameters.class
            );
            requireNonNull(parameters.temperature(), "temperature");
            require(parameters.temperature().compareTo(BigDecimal.ZERO) >= 0
                            && parameters.temperature().compareTo(BigDecimal.ONE) <= 0,
                    "temperature must be between 0 and 1");
            require(parameters.maxOutputTokens() > 0,
                    "maxOutputTokens must be positive");
            require(parameters.maxProviderCalls() >= 1
                            && parameters.maxProviderCalls() <= 2,
                    "maxProviderCalls must be 1 or 2");
            return parameters;
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "LLM generation parameters cannot be decoded",
                    exception
            );
        }
    }

    private String serialize(Object value, String label) {
        try {
            return canonicalWriter.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException(label + " cannot be encoded", exception);
        }
    }

    private String hash(LlmProviderRequest request) {
        RequestHashMaterial material = new RequestHashMaterial(
                request.providerCode(),
                request.requestedModel(),
                request.systemPrompt(),
                request.inputJson(),
                request.responseSchemaJson(),
                request.temperature(),
                request.maxOutputTokens()
        );
        try {
            byte[] canonical = canonicalWriter.writeValueAsBytes(material);
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical)
            );
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "LLM provider request hash cannot be encoded",
                    exception
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String minifiedJsonResource(String name) {
        try {
            return canonicalWriter.writeValueAsString(
                    objectMapper.readTree(resource(name))
            );
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Cannot canonicalize LLM resource: " + name,
                    exception
            );
        }
    }

    private String resource(String name) {
        ClassLoader classLoader = LlmProviderRequestFactory.class.getClassLoader();
        try (InputStream input = classLoader.getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalStateException("Missing LLM resource: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read LLM resource: " + name, exception);
        }
    }

    private record GenerationParameters(
            BigDecimal temperature,
            int maxOutputTokens,
            int maxProviderCalls
    ) {
    }

    private record RequestHashMaterial(
            String providerCode,
            String requestedModel,
            String systemPrompt,
            String inputJson,
            String responseSchemaJson,
            BigDecimal temperature,
            int maxOutputTokens
    ) {
    }
}
