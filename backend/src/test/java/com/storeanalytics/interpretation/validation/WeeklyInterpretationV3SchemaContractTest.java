package com.storeanalytics.interpretation.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.interpretation.contract.LlmContractResources;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class WeeklyInterpretationV3SchemaContractTest {

    private static final String READY_EXAMPLE =
            "contracts/llm/examples/weekly-interpretation-content-v3-ready.json";
    private static final String INSUFFICIENT_EXAMPLE =
            "contracts/llm/examples/"
                    + "weekly-interpretation-content-v3-insufficient-employee.json";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LlmJsonSchemaValidator validator = new LlmJsonSchemaValidator(
            LlmContractResources.PRIMARY_SIGNAL_CONTENT_SCHEMA
    );

    @Test
    void canonicalExamplesConformToPrimarySignalSchema() throws IOException {
        assertThat(validator.validate(resource(READY_EXAMPLE))).isEmpty();
        assertThat(validator.validate(resource(INSUFFICIENT_EXAMPLE))).isEmpty();
    }

    @Test
    void rootAddsOnlyTheVersionedPrimarySignalField() throws IOException {
        JsonNode schema = objectMapper.readTree(resource(
                LlmContractResources.PRIMARY_SIGNAL_CONTENT_SCHEMA
        ));

        assertThat(schema.path("properties").propertyNames())
                .containsExactlyInAnyOrderElementsOf(Set.of(
                        "employees",
                        "primarySignal",
                        "summaryBlocks",
                        "insights",
                        "actions",
                        "teamRelationships",
                        "dataLimitations"
                ));
    }

    @Test
    void registryPreservesOldPairsAndAddsV13ThroughV21WithSchemaV3() {
        assertThat(LlmContractResources.contentSchema(1))
                .isEqualTo(LlmContractResources.CONTENT_SCHEMA);
        assertThat(LlmContractResources.contentSchema(2))
                .isEqualTo(LlmContractResources.NEXT_CONTENT_SCHEMA);
        assertThat(LlmContractResources.contentSchema(3))
                .isEqualTo(LlmContractResources.PRIMARY_SIGNAL_CONTENT_SCHEMA);
        assertThat(LlmContractResources.systemPrompt("weekly-interpretation-v13"))
                .isEqualTo(LlmContractResources.PRIMARY_SIGNAL_SYSTEM_PROMPT);
        assertThat(LlmContractResources.systemPrompt("weekly-interpretation-v14"))
                .isEqualTo(LlmContractResources.STRUCTURED_SUMMARY_SYSTEM_PROMPT);
        assertThat(LlmContractResources.systemPrompt("weekly-interpretation-v15"))
                .isEqualTo(LlmContractResources
                        .TEAM_GUARDED_STRUCTURED_SUMMARY_SYSTEM_PROMPT);
        assertThat(LlmContractResources.systemPrompt("weekly-interpretation-v16"))
                .isEqualTo(LlmContractResources
                        .MATRIX_HARDENED_STRUCTURED_SUMMARY_SYSTEM_PROMPT);
        assertThat(LlmContractResources.systemPrompt("weekly-interpretation-v17"))
                .isEqualTo(LlmContractResources
                        .PRODUCTION_HARDENED_STRUCTURED_SUMMARY_SYSTEM_PROMPT);
        assertThat(LlmContractResources.systemPrompt("weekly-interpretation-v18"))
                .isEqualTo(LlmContractResources
                        .DETERMINISTIC_NARRATIVE_SYSTEM_PROMPT);
        assertThat(LlmContractResources.systemPrompt("weekly-interpretation-v19"))
                .isEqualTo(LlmContractResources
                        .PRIVACY_REDUCED_SYSTEM_PROMPT);
        assertThat(LlmContractResources.systemPrompt("weekly-interpretation-v20"))
                .isEqualTo(LlmContractResources
                        .MODERATION_SAFE_PRIVACY_REDUCED_SYSTEM_PROMPT);
        assertThat(LlmContractResources.systemPrompt("weekly-interpretation-v21"))
                .isEqualTo(LlmContractResources
                        .BOUNDED_PRIVACY_REDUCED_SYSTEM_PROMPT);
        assertThat(LlmContractResources.isSupportedPair(
                "weekly-interpretation-v13", 3
        )).isTrue();
        assertThat(LlmContractResources.isSupportedPair(
                "weekly-interpretation-v14", 3
        )).isTrue();
        assertThat(LlmContractResources.isSupportedPair(
                "weekly-interpretation-v15", 3
        )).isTrue();
        assertThat(LlmContractResources.isSupportedPair(
                "weekly-interpretation-v16", 3
        )).isTrue();
        assertThat(LlmContractResources.isSupportedPair(
                "weekly-interpretation-v17", 3
        )).isTrue();
        assertThat(LlmContractResources.isSupportedPair(
                "weekly-interpretation-v18", 3
        )).isTrue();
        assertThat(LlmContractResources.isSupportedPair(
                "weekly-interpretation-v19", 3
        )).isTrue();
        assertThat(LlmContractResources.isSupportedPair(
                "weekly-interpretation-v20", 3
        )).isTrue();
        assertThat(LlmContractResources.isSupportedPair(
                "weekly-interpretation-v21", 3
        )).isTrue();
        assertThat(LlmContractResources.isSupportedPair(
                "weekly-interpretation-v13", 2
        )).isFalse();
        assertThat(LlmContractResources.isSupportedPair(
                "weekly-interpretation-v14", 2
        )).isFalse();
        assertThat(LlmContractResources.isSupportedPair(
                "weekly-interpretation-v15", 2
        )).isFalse();
        assertThat(LlmContractResources.isSupportedPair(
                "weekly-interpretation-v16", 2
        )).isFalse();
        assertThat(LlmContractResources.isSupportedPair(
                "weekly-interpretation-v17", 2
        )).isFalse();
        assertThat(LlmContractResources.isSupportedPair(
                "weekly-interpretation-v19", 2
        )).isFalse();
        assertThat(LlmContractResources.isSupportedPair(
                "weekly-interpretation-v20", 2
        )).isFalse();
        assertThat(LlmContractResources.isSupportedPair(
                "weekly-interpretation-v21", 2
        )).isFalse();
        assertThat(LlmContractResources.isSupportedPair(
                "weekly-interpretation-v18", 2
        )).isFalse();
        assertThat(LlmContractResources.isSupportedPair(
                "weekly-interpretation-v12", 3
        )).isFalse();
    }

    private static String resource(String name) throws IOException {
        ClassLoader loader =
                WeeklyInterpretationV3SchemaContractTest.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalStateException("Missing test resource: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
