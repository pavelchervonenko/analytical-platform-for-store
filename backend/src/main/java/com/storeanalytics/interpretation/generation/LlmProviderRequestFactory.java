package com.storeanalytics.interpretation.generation;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.contract.LlmContractResources;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Period;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Manifest;
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
        this.systemPrompts = Map.of(
                LlmContractResources.PROMPT_VERSION,
                resource(LlmContractResources.SYSTEM_PROMPT),
                LlmContractResources.NEXT_PROMPT_VERSION,
                resource(LlmContractResources.NEXT_SYSTEM_PROMPT)
        );
        this.responseSchemas = Map.of(
                LlmContractResources.CONTENT_SCHEMA_VERSION,
                minifiedJsonResource(LlmContractResources.CONTENT_SCHEMA),
                LlmContractResources.NEXT_CONTENT_SCHEMA_VERSION,
                minifiedJsonResource(
                        LlmContractResources.NEXT_CONTENT_SCHEMA
                )
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
        WeeklyInterpretationInput input = inputCompactor.compact(input(snapshot));
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
                        value.contentSchemaVersion()
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
                providerManifest(snapshot),
                snapshot.payload().facts()
        );
    }

    private Manifest providerManifest(PersistedWeeklySnapshot snapshot) {
        Manifest source = snapshot.payload().manifest();
        return new Manifest(
                source.employeeRefs(),
                source.evidence().stream()
                        .filter(evidence -> !evidence.available())
                        .toList(),
                source.candidateRefs(),
                source.categoryCodes(),
                source.competencyCodes(),
                source.limitations()
        );
    }

    private String specializedResponseSchema(
            WeeklyInterpretationInput input,
            String responseSchema,
            int contentSchemaVersion
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
            boolean flatV2 = contentSchemaVersion
                    == LlmContractResources.NEXT_CONTENT_SCHEMA_VERSION;
            if (!flatV2) {
                collapseNullableUnions(schema);
            }
            inferPrimitiveTypesFromEnums(schema);
            stripProviderOnlyMetadata(schema, flatV2);
            int employeeCount = input.manifest().employeeRefs().size();
            ObjectNode employees = (ObjectNode) schema.at("/properties/employees");
            employees.put("minItems", employeeCount);
            employees.put("maxItems", employeeCount);
            if (flatV2) {
                constrainFlatProviderOutput(schema, employeeCount);
                constrainProviderReferences(schema, input);
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
            int employeeCount
    ) {
        requireMinimumCollectionSize(
                schema.at("/properties/summaryBlocks"),
                2 + employeeCount * 2
        );
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
        boundCollection(
                schema.at("/properties/teamRelationships"),
                Math.max(4, Math.min(8, 2 + employeeCount / 2))
        );
        removeProviderOwnedProperty(schema, "dataLimitations");
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
                    if (allowed.isEmpty()) {
                        if (field.getKey().endsWith("EmployeeRefs")
                                || "employeeRefs".equals(field.getKey())) {
                            setEmptyArray(field.getValue());
                        } else {
                            setNullOnly(field.getValue());
                        }
                        continue;
                    }
                    JsonNode target = field.getKey().endsWith("EmployeeRefs")
                            || "employeeRefs".equals(field.getKey())
                            ? field.getValue().path("items")
                            : concreteNullableAlternative(field.getValue());
                    setStringEnum(target, allowed);
                }
                constrainProviderReferences(field.getValue(), input);
            }
        }
        constrainProviderReferences(object.path("items"), input);
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
