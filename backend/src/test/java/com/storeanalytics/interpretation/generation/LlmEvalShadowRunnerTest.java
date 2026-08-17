package com.storeanalytics.interpretation.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class LlmEvalShadowRunnerTest {

    private final LlmEvalShadowRunner runner = new LlmEvalShadowRunner(
            new ObjectMapper().rebuild().findAndAddModules().build(),
            Clock.systemUTC()
    );

    @Test
    void defaultsToNetworkFreePlanWithPlaceholderModel() {
        LlmEvalShadowRunner.Settings settings =
                LlmEvalShadowRunner.Settings.from(Map.of());

        assertThat(settings.mode()).isEqualTo(LlmEvalShadowRunner.MODE_PLAN);
        assertThat(settings.placeholderModel()).isTrue();
        assertThat(settings.apiKey()).isEmpty();
        assertThat(settings.maxPaidCalls()).isZero();
        assertThat(settings.maxCostRub()).isNull();
    }

    @Test
    void rejectsMutableLatestModelBeforeAnyProviderCall() {
        Map<String, String> environment = executionEnvironment();
        environment.put(
                "YANDEX_AI_MODEL_URI",
                "gpt://folder1234/yandexgpt/latest"
        );

        assertThatThrownBy(() -> LlmEvalShadowRunner.Settings.from(environment))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be versioned");
    }

    @Test
    void rejectsZeroPriceCoefficientThatWouldDisableBudgetGuard() {
        Map<String, String> environment = executionEnvironment();
        environment.put("YANDEX_AI_INPUT_RUB_PER_THOUSAND_TOKENS", "0");

        assertThatThrownBy(() -> LlmEvalShadowRunner.Settings.from(environment))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("price coefficients must be positive");
    }

    @Test
    void rejectsStaleEvaluationArtifact(@TempDir Path directory)
            throws IOException {
        Path metadata = directory.resolve("receipt.json");
        Files.writeString(metadata, "{\"evaluationHash\":\"old\"}");

        assertThatThrownBy(() -> runner.verifyEvaluationArtifact(
                metadata, "current", "completed response"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Stale shadow completed response");

        Files.writeString(metadata, "{\"evaluationHash\":\"current\"}");
        assertThatCode(() -> runner.verifyEvaluationArtifact(
                metadata, "current", "completed response"
        )).doesNotThrowAnyException();
    }

    @Test
    void executionRequiresExplicitConfirmation() {
        Map<String, String> environment = executionEnvironment();
        environment.remove("CONFIRM_YANDEX_LLM_SHADOW");
        LlmEvalShadowRunner.Settings settings =
                LlmEvalShadowRunner.Settings.from(environment);

        assertThatThrownBy(() -> runner.validateExecution(
                settings,
                List.of(),
                BigDecimal.ZERO
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CONFIRM_YANDEX_LLM_SHADOW");
    }

    @Test
    void executionRejectsSelectedMaximumCostAboveExplicitCap() {
        LlmEvalShadowRunner.Settings settings =
                LlmEvalShadowRunner.Settings.from(executionEnvironment());

        assertThatThrownBy(() -> runner.validateExecution(
                settings,
                List.of(),
                new BigDecimal("10.000001")
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("above the explicit cap");
    }

    @Test
    void fullyConfiguredEmptyExecutionSelectionIsSafe() {
        LlmEvalShadowRunner.Settings settings =
                LlmEvalShadowRunner.Settings.from(executionEnvironment());

        assertThatCode(() -> runner.validateExecution(
                settings,
                List.of(),
                BigDecimal.ZERO
        )).doesNotThrowAnyException();
    }

    private Map<String, String> executionEnvironment() {
        Map<String, String> values = new HashMap<>();
        values.put("LLM_EVAL_MODE", "execute");
        values.put("YANDEX_AI_FOLDER_ID", "folder1234");
        values.put(
                "YANDEX_AI_MODEL_URI",
                "gpt://folder1234/yandexgpt-5.1"
        );
        values.put("YANDEX_AI_API_KEY", "synthetic-test-secret");
        values.put("LLM_EVAL_MAX_PAID_CALLS", "1");
        values.put("LLM_EVAL_MAX_COST_RUB", "10.00");
        values.put(
                "CONFIRM_YANDEX_LLM_SHADOW",
                LlmEvalShadowRunner.EXECUTION_CONFIRMATION
        );
        return values;
    }
}
