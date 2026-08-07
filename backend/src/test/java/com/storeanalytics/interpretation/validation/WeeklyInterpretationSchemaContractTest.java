package com.storeanalytics.interpretation.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.interpretation.contract.LlmContractResources;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class WeeklyInterpretationSchemaContractTest {

    private static final String READY_EXAMPLE =
            "contracts/llm/examples/weekly-interpretation-content-v1-ready.json";
    private static final String INSUFFICIENT_EXAMPLE =
            "contracts/llm/examples/"
                    + "weekly-interpretation-content-v1-insufficient-employee.json";
    private static final String INPUT_EXAMPLE =
            "contracts/llm/examples/weekly-interpretation-input-v1-minimal.json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private LlmJsonSchemaValidator contentValidator;
    private LlmJsonSchemaValidator inputValidator;

    @BeforeEach
    void setUp() {
        contentValidator = new LlmJsonSchemaValidator(
                LlmContractResources.CONTENT_SCHEMA
        );
        inputValidator = new LlmJsonSchemaValidator(
                LlmContractResources.INPUT_SCHEMA
        );
    }

    @Test
    void canonicalExamplesConformToTheirSchemas() throws IOException {
        assertThat(contentValidator.validate(resource(READY_EXAMPLE))).isEmpty();
        assertThat(contentValidator.validate(resource(INSUFFICIENT_EXAMPLE))).isEmpty();
        assertThat(inputValidator.validate(resource(INPUT_EXAMPLE))).isEmpty();
    }

    @Test
    void rejectsUnknownProperty() throws IOException {
        ObjectNode root = readyContent();
        root.put("unexpected", true);

        assertViolation(root, "additionalProperties");
    }

    @Test
    void rejectsMissingRequiredProperty() throws IOException {
        ObjectNode store = (ObjectNode) readyContent().get("store");
        store.remove("headline");

        assertViolation(storeRoot(store), "required");
    }

    @Test
    void rejectsUnknownEnum() throws IOException {
        ObjectNode root = readyContent();
        ObjectNode strength = (ObjectNode) root.at("/store/strength");
        strength.put("kind", "UNSUPPORTED");

        assertViolation(root, "enum");
    }

    @Test
    void rejectsForbiddenNull() throws IOException {
        ObjectNode root = readyContent();
        ObjectNode headline = (ObjectNode) root.at("/store/headline");
        headline.putNull("text");

        assertViolation(root, "type");
    }

    @Test
    void rejectsExceededCardinality() throws IOException {
        ObjectNode root = readyContent();
        ArrayNode actions = (ArrayNode) root.at("/store/recommendedActions");
        JsonNode action = actions.get(0).deepCopy();
        actions.add(action.deepCopy());
        actions.add(action.deepCopy());
        actions.add(action.deepCopy());

        assertViolation(root, "maxItems");
    }

    @Test
    void rejectsDuplicateUniqueItem() throws IOException {
        ObjectNode root = readyContent();
        ArrayNode evidence = (ArrayNode) root.at("/store/headline/evidenceRefs");
        evidence.add(evidence.get(0).textValue());

        assertViolation(root, "uniqueItems");
    }

    @Test
    void rejectsInvalidIdentifierPattern() throws IOException {
        ObjectNode root = readyContent();
        ObjectNode employee = (ObjectNode) root.at("/employees/0");
        employee.put("employeeRef", "employee-1");

        assertViolation(root, "pattern");
    }

    private ObjectNode readyContent() throws IOException {
        return (ObjectNode) objectMapper.readTree(resource(READY_EXAMPLE));
    }

    private ObjectNode storeRoot(ObjectNode changedStore) throws IOException {
        ObjectNode root = readyContent();
        root.set("store", changedStore);
        return root;
    }

    private void assertViolation(JsonNode content, String expectedKeyword)
            throws IOException {
        List<StructuralValidationViolation> violations = contentValidator.validate(
                objectMapper.writeValueAsString(content)
        );

        assertThat(violations)
                .extracting(StructuralValidationViolation::keyword)
                .contains(expectedKeyword);
        assertThat(violations)
                .extracting(StructuralValidationViolation::path)
                .allSatisfy(path -> assertThat(path).isNotNull());
    }

    private static String resource(String name) throws IOException {
        ClassLoader classLoader = WeeklyInterpretationSchemaContractTest.class.getClassLoader();
        try (InputStream input = classLoader.getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalStateException("Missing test resource: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
