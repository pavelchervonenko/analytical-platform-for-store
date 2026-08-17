package com.storeanalytics.interpretation.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.interpretation.contract.LlmContractResources;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class WeeklyInterpretationV2SchemaContractTest {

    private static final String READY_EXAMPLE =
            "contracts/llm/examples/weekly-interpretation-content-v2-ready.json";
    private static final String INSUFFICIENT_EXAMPLE =
            "contracts/llm/examples/"
                    + "weekly-interpretation-content-v2-insufficient-employee.json";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LlmJsonSchemaValidator validator = new LlmJsonSchemaValidator(
            LlmContractResources.NEXT_CONTENT_SCHEMA
    );

    @Test
    void canonicalExamplesConformToFlatSchema() throws IOException {
        assertThat(validator.validate(resource(READY_EXAMPLE))).isEmpty();
        assertThat(validator.validate(resource(INSUFFICIENT_EXAMPLE))).isEmpty();
    }

    @Test
    void permitsStoreOnlyInterpretationWhenSnapshotHasNoEmployees()
            throws IOException {
        ObjectNode root = (ObjectNode) objectMapper.readTree(resource(READY_EXAMPLE));
        root.putArray("employees");

        assertThat(validator.validate(objectMapper.writeValueAsString(root))).isEmpty();
    }


    @Test
    void mostImprovedRelationshipAcceptsRequiredNullCompetency() throws IOException {
        ObjectNode root = (ObjectNode) objectMapper.readTree(resource(READY_EXAMPLE));
        ArrayNode relationships = (ArrayNode) root.path("teamRelationships");
        ObjectNode mostImproved = relationships.addObject();
        mostImproved.put("type", "MOST_IMPROVED");
        mostImproved.putNull("competencyCode");
        mostImproved.putArray("sourceEmployeeRefs").add("E01");
        mostImproved.putArray("targetEmployeeRefs");
        mostImproved.put(
                "summary",
                "Сотрудник показывает наиболее заметное улучшение."
        );
        mostImproved.putArray("evidenceRefs")
                .add("EMP:E01.GROUP:SERVICE.REVENUE_SHARE_PERCENT.CURRENT");

        assertThat(validator.validate(objectMapper.writeValueAsString(root))).isEmpty();
    }
    @Test
    void rootContainsOnlyFlatCollections() throws IOException {
        JsonNode schema = objectMapper.readTree(resource(
                LlmContractResources.NEXT_CONTENT_SCHEMA
        ));

        assertThat(schema.path("properties").propertyNames())
                .containsExactlyInAnyOrderElementsOf(Set.of(
                        "employees",
                        "summaryBlocks",
                        "insights",
                        "actions",
                        "teamRelationships",
                        "dataLimitations"
                ));
        assertThat(countDeclaredProperties(schema)).isLessThanOrEqualTo(60);
    }

    @Test
    void rejectsNestedOrUnknownProviderShape() throws IOException {
        ObjectNode root = (ObjectNode) objectMapper.readTree(resource(READY_EXAMPLE));
        ObjectNode insight = (ObjectNode) root.path("insights").get(0);
        insight.putObject("categoryPerformance").put("summary", "unsupported");

        assertThat(validator.validate(objectMapper.writeValueAsString(root)))
                .extracting(StructuralValidationViolation::keyword)
                .contains("additionalProperties");
    }

    @Test
    void contractRegistryPreservesV1AndRegistersV2() {
        assertThat(LlmContractResources.contentSchema(1))
                .isEqualTo(LlmContractResources.CONTENT_SCHEMA);
        assertThat(LlmContractResources.contentSchema(2))
                .isEqualTo(LlmContractResources.NEXT_CONTENT_SCHEMA);
        assertThat(LlmContractResources.systemPrompt("weekly-interpretation-v3"))
                .isEqualTo(LlmContractResources.SYSTEM_PROMPT);
        assertThat(LlmContractResources.systemPrompt("weekly-interpretation-v4"))
                .isEqualTo(LlmContractResources.NEXT_SYSTEM_PROMPT);
        assertThat(LlmContractResources.systemPrompt("weekly-interpretation-v5"))
                .isEqualTo(LlmContractResources.CONCISE_SYSTEM_PROMPT);
        assertThat(LlmContractResources.systemPrompt("weekly-interpretation-v6"))
                .isEqualTo(
                        LlmContractResources.REVISED_CONCISE_SYSTEM_PROMPT
                );
        assertThat(LlmContractResources.isSupportedPair(
                "weekly-interpretation-v5", 2
        )).isTrue();
        assertThat(LlmContractResources.isSupportedPair(
                "weekly-interpretation-v6", 2
        )).isTrue();
        assertThat(LlmContractResources.systemPrompt("weekly-interpretation-v7"))
                .isEqualTo(
                        LlmContractResources.STRICT_CONCISE_SYSTEM_PROMPT
                );
        assertThat(LlmContractResources.isSupportedPair(
                "weekly-interpretation-v7", 2
        )).isTrue();
        assertThat(LlmContractResources.isStrictConcisePrompt(
                "weekly-interpretation-v7"
        )).isTrue();
        assertThat(LlmContractResources.systemPrompt("weekly-interpretation-v8"))
                .isEqualTo(
                        LlmContractResources.ACTIONABLE_CONCISE_SYSTEM_PROMPT
                );
        assertThat(LlmContractResources.isSupportedPair(
                "weekly-interpretation-v8", 2
        )).isTrue();
        assertThat(LlmContractResources.isStrictConcisePrompt(
                "weekly-interpretation-v8"
        )).isTrue();
        assertThat(LlmContractResources.systemPrompt(
                "weekly-interpretation-v9"
        )).isEqualTo(
                LlmContractResources.EVIDENCE_GUARDED_SYSTEM_PROMPT
        );
        assertThat(LlmContractResources.isSupportedPair(
                "weekly-interpretation-v9", 2
        )).isTrue();
        assertThat(LlmContractResources.isStrictConcisePrompt(
                "weekly-interpretation-v9"
        )).isTrue();
        assertThat(LlmContractResources.isEvidenceGuardedPrompt(
                "weekly-interpretation-v9"
        )).isTrue();
        assertThat(LlmContractResources.systemPrompt(
                "weekly-interpretation-v10"
        )).isEqualTo(
                LlmContractResources.HARDENED_EVIDENCE_SYSTEM_PROMPT
        );
        assertThat(LlmContractResources.isSupportedPair(
                "weekly-interpretation-v10", 2
        )).isTrue();
        assertThat(LlmContractResources.isStrictConcisePrompt(
                "weekly-interpretation-v10"
        )).isTrue();
        assertThat(LlmContractResources.isEvidenceGuardedPrompt(
                "weekly-interpretation-v10"
        )).isTrue();
        assertThat(LlmContractResources.systemPrompt(
                "weekly-interpretation-v11"
        )).isEqualTo(
                LlmContractResources.NARRATIVE_GUARDED_SYSTEM_PROMPT
        );
        assertThat(LlmContractResources.isSupportedPair(
                "weekly-interpretation-v11", 2
        )).isTrue();
        assertThat(LlmContractResources.isStrictConcisePrompt(
                "weekly-interpretation-v11"
        )).isTrue();
        assertThat(LlmContractResources.isEvidenceGuardedPrompt(
                "weekly-interpretation-v11"
        )).isTrue();
        assertThat(LlmContractResources.systemPrompt(
                "weekly-interpretation-v12"
        )).isEqualTo(
                LlmContractResources.CAUSAL_NARRATIVE_GUARDED_SYSTEM_PROMPT
        );
        assertThat(LlmContractResources.isSupportedPair(
                "weekly-interpretation-v12", 2
        )).isTrue();
        assertThat(LlmContractResources.isStrictConcisePrompt(
                "weekly-interpretation-v12"
        )).isTrue();
        assertThat(LlmContractResources.isEvidenceGuardedPrompt(
                "weekly-interpretation-v12"
        )).isTrue();
        assertThatThrownBy(() -> LlmContractResources.contentSchema(4))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private int countDeclaredProperties(JsonNode node) {
        int count = node.path("properties").size();
        for (JsonNode child : node) {
            count += countDeclaredProperties(child);
        }
        return count;
    }

    private static String resource(String name) throws IOException {
        ClassLoader classLoader =
                WeeklyInterpretationV2SchemaContractTest.class.getClassLoader();
        try (InputStream input = classLoader.getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalStateException("Missing test resource: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
