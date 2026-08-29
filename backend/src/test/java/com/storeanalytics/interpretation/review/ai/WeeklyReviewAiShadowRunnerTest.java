package com.storeanalytics.interpretation.review.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WeeklyReviewAiShadowRunnerTest {

    @Test
    void defaultsToNetworkFreePlanWithoutPaidCalls() {
        WeeklyReviewAiShadowRunner.Settings settings =
                WeeklyReviewAiShadowRunner.Settings.from(Map.of());

        assertThat(settings.mode()).isEqualTo(WeeklyReviewAiShadowRunner.MODE_PLAN);
        assertThat(settings.maxPaidCalls()).isZero();
        assertThat(settings.caseOffset()).isZero();
        assertThat(settings.modelUri()).doesNotEndWith("/latest");
    }

    @Test
    void executeRequiresExplicitCallsBudgetAndConfirmation() {
        Map<String, String> environment = executionEnvironment();
        environment.remove("CONFIRM_WEEKLY_REVIEW_AI_SHADOW");
        WeeklyReviewAiShadowRunner.Settings settings =
                WeeklyReviewAiShadowRunner.Settings.from(environment);

        assertThatThrownBy(() -> settings.validateExecution(BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CONFIRM_WEEKLY_REVIEW_AI_SHADOW");
    }

    @Test
    void executeRejectsMutableModelAndMaximumAboveCap() {
        Map<String, String> mutable = executionEnvironment();
        mutable.put("YANDEX_AI_MODEL_URI", "gpt://folder/model/latest");
        assertThatThrownBy(() ->
                WeeklyReviewAiShadowRunner.Settings.from(mutable)
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("versioned model");

        WeeklyReviewAiShadowRunner.Settings settings =
                WeeklyReviewAiShadowRunner.Settings.from(executionEnvironment());
        assertThatThrownBy(() -> settings.validateExecution(
                new BigDecimal("5.01")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("above explicit cap");

        Map<String, String> invalidOffset = executionEnvironment();
        invalidOffset.put("WEEKLY_REVIEW_AI_EVAL_CASE_OFFSET", "4");
        assertThatThrownBy(() ->
                WeeklyReviewAiShadowRunner.Settings.from(invalidOffset)
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bounded paid calls");
    }

    private Map<String, String> executionEnvironment() {
        Map<String, String> values = new HashMap<>();
        values.put("WEEKLY_REVIEW_AI_EVAL_MODE", "execute");
        values.put("YANDEX_AI_FOLDER_ID", "folder");
        values.put("YANDEX_AI_API_KEY", "synthetic-secret");
        values.put("YANDEX_AI_MODEL_URI", "gpt://folder/yandexgpt-5.1");
        values.put("WEEKLY_REVIEW_AI_EVAL_MAX_PAID_CALLS", "1");
        values.put("WEEKLY_REVIEW_AI_EVAL_MAX_COST_RUB", "5.00");
        values.put("WEEKLY_REVIEW_AI_EVAL_OUTPUT_DIR", "build/weekly-review-ai-eval/run-1");
        values.put(
                "CONFIRM_WEEKLY_REVIEW_AI_SHADOW",
                WeeklyReviewAiShadowRunner.CONFIRMATION
        );
        return values;
    }
}
