package com.storeanalytics.interpretation.review.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.interpretation.validation.LlmJsonSchemaValidator;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class WeeklyReviewAiSchemaContractTest {

    private static final String READY_EXAMPLE =
            "contracts/llm/examples/weekly-review-ai-content-v4-ready.json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void canonicalExampleConformsToSchemaFour() throws IOException {
        LlmJsonSchemaValidator validator = new LlmJsonSchemaValidator(
                WeeklyReviewAiContract.CONTENT_SCHEMA
        );

        assertThat(validator.validate(resource(READY_EXAMPLE))).isEmpty();
    }

    @Test
    void schemaContainsOnlyOptionalStoreLevelEnrichment() throws IOException {
        JsonNode schema = objectMapper.readTree(resource(
                WeeklyReviewAiContract.CONTENT_SCHEMA
        ));

        assertThat(schema.path("properties").propertyNames())
                .containsExactlyInAnyOrderElementsOf(Set.of(
                        "schemaVersion",
                        "summary",
                        "factorExplanations",
                        "actionWordings"
                ));
        assertThat(schema.path("required")).hasSize(4);
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(resource(WeeklyReviewAiContract.CONTENT_SCHEMA))
                .doesNotContain(
                        "\"employees\"",
                        "\"employeePublicId\"",
                        "\"monthlyPlan\"",
                        "\"period\"",
                        "\"target\"",
                        "\"displayName\""
                );
    }

    @Test
    void contractUsesNewImmutableVersionWithoutChangingLegacyRegistry() {
        assertThat(WeeklyReviewAiContract.PROMPT_VERSION)
                .isEqualTo("weekly-interpretation-v24");
        assertThat(WeeklyReviewAiContract.INPUT_SCHEMA_VERSION).isEqualTo(3);
        assertThat(WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION).isEqualTo(4);
        assertThat(WeeklyReviewAiContract.SYSTEM_PROMPT)
                .isEqualTo("prompts/llm/weekly-interpretation-v24.md");
        assertThat(WeeklyReviewAiContract.isReadable(
                WeeklyReviewAiContract.PREVIOUS_PROMPT_VERSION, 4
        )).isTrue();
        assertThat(WeeklyReviewAiContract.isReadable(
                WeeklyReviewAiContract.LEGACY_PROMPT_VERSION, 4
        )).isTrue();
        assertThat(WeeklyReviewAiContract.isActive(
                WeeklyReviewAiContract.LEGACY_PROMPT_VERSION, 4
        )).isFalse();
    }

    private static String resource(String name) throws IOException {
        ClassLoader loader = WeeklyReviewAiSchemaContractTest.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalStateException("Missing test resource: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
