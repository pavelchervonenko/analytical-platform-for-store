package com.storeanalytics.interpretation.review.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class WeeklyReviewAiManagementPromptContractTest {

    @Test
    void activePromptLimitsProviderToEditorialSelectors()
            throws IOException {
        String prompt = resource(WeeklyReviewAiContract.SYSTEM_PROMPT);

        assertThat(WeeklyReviewAiContract.PROMPT_VERSION)
                .isEqualTo("weekly-interpretation-v25");
        assertThat(WeeklyReviewAiContract.SELECTION_SCHEMA_VERSION).isOne();
        assertThat(prompt).contains(
                "не пишешь пользовательский текст",
                "только разрешённые selector-токены",
                "summary.allowedSelectors",
                "SUMMARY_OUTCOME",
                "SUMMARY_STRENGTH",
                "SUMMARY_RISK",
                "SUMMARY_BALANCED",
                "factorSelections",
                "ровно один раз и в исходном порядке"
        );
    }

    @Test
    void activePromptKeepsBackendOwnershipAndPrivacyBoundary()
            throws IOException {
        String prompt = resource(WeeklyReviewAiContract.SYSTEM_PROMPT);

        assertThat(prompt).contains(
                "Не создавай текст, числа, даты, причины, советы",
                "только данные магазина",
                "reportState=PARTIAL",
                "backend сам добавит ограничение",
                "Не меняй эффект factor",
                "Не используй причинность как основание выбора",
                "Используй только selectors и factor IDs"
        );
        assertThat(prompt).doesNotContain(
                "скопируй её в",
                "managementMeaning",
                "allowedNarratives"
        );
    }

    @Test
    void threePreviousPromptsRemainPackagedForAuditAndRollback()
            throws IOException {
        String v24 = resource(
                "prompts/llm/weekly-interpretation-v24.md"
        );
        String v23 = resource(
                "prompts/llm/weekly-interpretation-v23.md"
        );
        String v22 = resource(
                "prompts/llm/weekly-interpretation-v22.md"
        );
        String active = resource(WeeklyReviewAiContract.SYSTEM_PROMPT);

        assertThat(v24).isNotBlank();
        assertThat(v23).isNotBlank();
        assertThat(v22).isNotBlank();
        assertThat(active)
                .isNotEqualTo(v24)
                .isNotEqualTo(v23)
                .isNotEqualTo(v22);
    }

    private static String resource(String name) throws IOException {
        ClassLoader loader = WeeklyReviewAiManagementPromptContractTest.class
                .getClassLoader();
        try (InputStream input = loader.getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Missing test resource: " + name
                );
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
