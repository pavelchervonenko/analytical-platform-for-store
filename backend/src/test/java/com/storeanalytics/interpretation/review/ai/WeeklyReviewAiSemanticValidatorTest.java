package com.storeanalytics.interpretation.review.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.interpretation.validation.LlmValidationOutcome;
import java.util.List;
import org.junit.jupiter.api.Test;

class WeeklyReviewAiSemanticValidatorTest {

    private final WeeklyReviewAiSemanticValidator validator =
            new WeeklyReviewAiSemanticValidator(
                    new WeeklyReviewAiStructuralValidator()
            );

    @Test
    void acceptsExactStoreOnlyEnrichment() {
        WeeklyReviewAiValidationResult result = validator.validate(
                input(false), validResponse()
        );

        assertThat(result.outcome()).isEqualTo(LlmValidationOutcome.VALID);
        assertThat(result.content()).isNotNull();
        assertThat(result.semanticValidated()).isTrue();
    }

    @Test
    void rejectsAddedRemovedOrReorderedBackendObjects() {
        assertViolation("""
                {
                  "schemaVersion": 4,
                  "summary": {
                    "text": "Выручка выросла, возвраты требуют внимания.",
                    "evidenceRefs": ["STORE.NET_REVENUE", "STORE.RETURN_REVENUE"]
                  },
                  "factorExplanations": [],
                  "actionWordings": [
                    {
                      "actionId": "action:restore:return_revenue",
                      "title": "Проверить возвраты",
                      "check": "Сравнить со следующей полной неделей"
                    }
                  ]
                }
                """, "FACTOR_SET_MISMATCH");

        assertViolation(validResponse().replace(
                "action:restore:return_revenue",
                "action:invented"
        ), "ACTION_SET_MISMATCH");
    }

    @Test
    void rejectsEvidenceOutsideObjectAllowlist() {
        assertViolation(validResponse().replace(
                "\"evidenceRefs\": [\"STORE.RETURN_REVENUE\"]",
                "\"evidenceRefs\": [\"STORE.NET_REVENUE\"]"
        ), "FACTOR_EVIDENCE_MISMATCH");
    }

    @Test
    void rejectsNewNumbersAndUnapprovedCausalLanguage() {
        assertViolation(validResponse().replace(
                "Возвраты выросли относительно предыдущей недели.",
                "Возвраты выросли на 12% относительно предыдущей недели."
        ), "UNAPPROVED_NUMBER");

        assertViolation(validResponse().replace(
                "Возвраты выросли относительно предыдущей недели.",
                "Выручка изменилась из-за роста возвратов."
        ), "UNAPPROVED_CAUSALITY");

        WeeklyReviewAiValidationResult causal = validator.validate(
                input(true),
                validResponse().replace(
                        "Возвраты выросли относительно предыдущей недели.",
                        "Выручка изменилась из-за роста возвратов."
                )
        );
        assertThat(causal.outcome()).isEqualTo(LlmValidationOutcome.VALID);
    }

    @Test
    void rejectsPlanAndDuplicateNarrative() {
        assertViolation(validResponse().replace(
                "Выручка выросла, возвраты требуют внимания.",
                "План месяца требует внимания."
        ), "FORBIDDEN_HORIZON");

        assertViolation(validResponse().replace(
                "Проверить возвраты",
                "Возвраты выросли относительно предыдущей недели."
        ), "DUPLICATE_NARRATIVE");
    }

    @Test
    void rejectsChangedBackendOwnedActionCheck() {
        assertViolation(validResponse().replace(
                "Сравнить со следующей полной неделей",
                "Проверить результат через две недели"
        ), "ACTION_CHECK_MISMATCH");
    }

    private void assertViolation(String response, String code) {
        WeeklyReviewAiValidationResult result = validator.validate(
                input(false), response
        );

        assertThat(result.outcome())
                .isEqualTo(LlmValidationOutcome.SEMANTIC_INVALID);
        assertThat(result.violations())
                .extracting(value -> value.code())
                .contains(code);
    }

    private WeeklyReviewAiInput input(boolean causalLanguageAllowed) {
        return new WeeklyReviewAiInput(
                1,
                "weekly-interpretation-v22",
                4,
                new WeeklyReviewAiInput.SummarySource(
                        "Выручка выросла, возвраты требуют внимания.",
                        List.of(
                                "STORE.NET_REVENUE",
                                "STORE.RETURN_REVENUE"
                        ),
                        List.of()
                ),
                List.of(new WeeklyReviewAiInput.FactorSource(
                        "factor:return_revenue",
                        "Возвраты выросли",
                        "Возвраты выросли относительно предыдущей недели.",
                        "NEGATIVE",
                        causalLanguageAllowed,
                        List.of("STORE.RETURN_REVENUE"),
                        List.of()
                )),
                List.of(new WeeklyReviewAiInput.ActionSource(
                        "action:restore:return_revenue",
                        "Проверить возвраты",
                        "Сравнить со следующей полной неделей",
                        List.of("STORE.RETURN_REVENUE"),
                        List.of()
                )),
                List.of(
                        evidence("STORE.NET_REVENUE", "Чистая выручка"),
                        evidence("STORE.RETURN_REVENUE", "Возвраты")
                )
        );
    }

    private WeeklyReviewAiInput.EvidenceSource evidence(
            String reference,
            String label
    ) {
        return new WeeklyReviewAiInput.EvidenceSource(
                reference, label, "RUB", "available", "available"
        );
    }

    private String validResponse() {
        return """
                {
                  "schemaVersion": 4,
                  "summary": {
                    "text": "Выручка выросла, возвраты требуют внимания.",
                    "evidenceRefs": ["STORE.NET_REVENUE", "STORE.RETURN_REVENUE"]
                  },
                  "factorExplanations": [
                    {
                      "factorId": "factor:return_revenue",
                      "text": "Возвраты выросли относительно предыдущей недели.",
                      "evidenceRefs": ["STORE.RETURN_REVENUE"]
                    }
                  ],
                  "actionWordings": [
                    {
                      "actionId": "action:restore:return_revenue",
                      "title": "Проверить возвраты",
                      "check": "Сравнить со следующей полной неделей"
                    }
                  ]
                }
                """;
    }
}
