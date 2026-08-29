package com.storeanalytics.interpretation.review.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.interpretation.validation.LlmValidationOutcome;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WeeklyReviewAiStructuralValidatorTest {

    private static final String READY_EXAMPLE =
            "contracts/llm/examples/weekly-review-ai-content-v4-ready.json";

    private final WeeklyReviewAiStructuralValidator validator =
            new WeeklyReviewAiStructuralValidator();

    @Test
    void decodesStrictTypedContentAndReturnsCanonicalJson() throws IOException {
        WeeklyReviewAiValidationResult result = validator.validate(resource(
                READY_EXAMPLE
        ));

        assertThat(result.outcome()).isEqualTo(LlmValidationOutcome.VALID);
        assertThat(result.content().schemaVersion()).isEqualTo(4);
        assertThat(result.semanticValidated()).isFalse();
        assertThat(result.content().factorExplanations())
                .extracting(WeeklyReviewAiContent.FactorExplanation::factorId)
                .containsExactly("factor:return_revenue");
        assertThat(result.canonicalContent()).contains("\"schemaVersion\":4");
        assertThat(result.violations()).isEmpty();
    }

    @Test
    void rejectsUnknownFieldsInsteadOfNormalizingProviderOutput() {
        String body = """
                {
                  "schemaVersion": 4,
                  "summary": {"text": "Итог", "evidenceRefs": ["STORE.NET_REVENUE"]},
                  "factorExplanations": [],
                  "actionWordings": [],
                  "employees": []
                }
                """;

        WeeklyReviewAiValidationResult result = validator.validate(body);

        assertThat(result.outcome())
                .isEqualTo(LlmValidationOutcome.STRUCTURAL_INVALID);
        assertThat(result.content()).isNull();
        assertThat(result.canonicalContent()).isNull();
        assertThat(result.violations()).isNotEmpty();
    }

    @Test
    void rejectsMissingCollectionsWrongVersionAndDuplicateIds() {
        assertThat(validator.validate("""
                {
                  "schemaVersion": 3,
                  "summary": {"text": "Итог", "evidenceRefs": ["STORE.NET_REVENUE"]}
                }
                """).outcome()).isEqualTo(LlmValidationOutcome.STRUCTURAL_INVALID);

        WeeklyReviewAiValidationResult duplicates = validator.validate("""
                {
                  "schemaVersion": 4,
                  "summary": {"text": "Итог", "evidenceRefs": ["STORE.NET_REVENUE"]},
                  "factorExplanations": [
                    {
                      "factorId": "factor:one",
                      "text": "Первое пояснение",
                      "evidenceRefs": ["STORE.NET_REVENUE"]
                    },
                    {
                      "factorId": "factor:one",
                      "text": "Другое пояснение",
                      "evidenceRefs": ["STORE.GROSS_PROFIT"]
                    }
                  ],
                  "actionWordings": []
                }
                """);

        assertThat(duplicates.outcome())
                .isEqualTo(LlmValidationOutcome.STRUCTURAL_INVALID);
        assertThat(duplicates.violations())
                .extracting(value -> value.code())
                .contains("TYPED_CONTRACT");
    }

    @Test
    void typedCollectionsAreDefensive() {
        List<String> references = new ArrayList<>(List.of("STORE.NET_REVENUE"));
        List<WeeklyReviewAiContent.FactorExplanation> factors = new ArrayList<>();
        WeeklyReviewAiContent content = new WeeklyReviewAiContent(
                4,
                new WeeklyReviewAiContent.Summary("Итог недели", references),
                factors,
                List.of()
        );

        references.add("STORE.GROSS_PROFIT");
        factors.add(new WeeklyReviewAiContent.FactorExplanation(
                "factor:late",
                "Позднее изменение",
                List.of("STORE.GROSS_PROFIT")
        ));

        assertThat(content.summary().evidenceRefs())
                .containsExactly("STORE.NET_REVENUE");
        assertThat(content.factorExplanations()).isEmpty();
    }

    private static String resource(String name) throws IOException {
        ClassLoader loader = WeeklyReviewAiStructuralValidatorTest.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalStateException("Missing test resource: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
