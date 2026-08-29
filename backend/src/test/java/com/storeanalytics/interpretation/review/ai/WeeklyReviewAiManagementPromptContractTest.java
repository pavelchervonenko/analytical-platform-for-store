package com.storeanalytics.interpretation.review.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class WeeklyReviewAiManagementPromptContractTest {

    @Test
    void activePromptRequiresManagementReadingInsteadOfNumericRestatement()
            throws IOException {
        String prompt = resource(WeeklyReviewAiContract.SYSTEM_PROMPT);

        assertThat(WeeklyReviewAiContract.PROMPT_VERSION)
                .isEqualTo("weekly-interpretation-v24");
        assertThat(WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION).isEqualTo(4);
        assertThat(prompt).contains(
                "не пересказать таблицу",
                "управленческое чтение",
                "summary.allowedNarratives",
                "скопируй её в `summary.text` дословно",
                "`summary.outcomeEffect` и `summary.outcomeText`",
                "managementMeaning",
                "периодом сравнения",
                "точной формуле без дополнительных слов",
                "короткая проверяемая команда из 2–8 слов",
                "Скопируй его из input",
                "не заменяй глагол"
        );
    }

    @Test
    void activePromptKeepsBackendOwnershipAndPrivacyBoundary()
            throws IOException {
        String prompt = resource(WeeklyReviewAiContract.SYSTEM_PROMPT);

        assertThat(prompt).contains(
                "Не рассчитывай и не изменяй числа",
                "Не придумывай причины",
                "Поле `check` также скопируй из input дословно",
                "Не создавай персональный контент",
                "Не упоминай месячный план",
                "не доказывает тренд или тенденцию",
                "советы, поручения, операции",
                "возможное влияние, другие показатели",
                "не добавляй слово «полная»",
                "ровно одним предложением",
                "подготовленное backend безопасное объяснение",
                "Показатели отдельных factors не переноси в summary"
        );
    }

    @Test
    void previousPromptRemainsPackagedForAuditAndRollback() throws IOException {
        String previous = resource(
                "prompts/llm/weekly-interpretation-v23.md"
        );
        String legacy = resource(
                "prompts/llm/weekly-interpretation-v22.md"
        );
        String active = resource(WeeklyReviewAiContract.SYSTEM_PROMPT);

        assertThat(previous).isNotBlank();
        assertThat(legacy).isNotBlank();
        assertThat(active).isNotEqualTo(previous).isNotEqualTo(legacy);
    }

    private static String resource(String name) throws IOException {
        ClassLoader loader = WeeklyReviewAiManagementPromptContractTest.class
                .getClassLoader();
        try (InputStream input = loader.getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalStateException("Missing test resource: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
