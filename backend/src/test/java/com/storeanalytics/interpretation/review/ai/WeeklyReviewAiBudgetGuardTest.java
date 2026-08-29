package com.storeanalytics.interpretation.review.ai;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.interpretation.generation.LlmProviderPreflight;
import com.storeanalytics.interpretation.generation.LlmProviderRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WeeklyReviewAiBudgetGuardTest {

    private final WeeklyReviewAiBudgetGuard guard = new WeeklyReviewAiBudgetGuard(
            WeeklyReviewAiTestProperties.properties(true, false, true)
    );

    @Test
    void enforcesCallContextCurrencyAndDailyBudgets() {
        LlmProviderRequest request = request("input", 1400);
        LlmProviderPreflight accepted = new LlmProviderPreflight(
                1000, 8000, new BigDecimal("9.00"), "RUB"
        );

        assertThatCode(() -> guard.validate(
                request, accepted, new BigDecimal("90.00")
        )).doesNotThrowAnyException();
        assertCode(request, new LlmProviderPreflight(
                7000, 8000, new BigDecimal("9.00"), "RUB"
        ), BigDecimal.ZERO, "CONTEXT_WINDOW_EXCEEDED");
        assertCode(request, new LlmProviderPreflight(
                1000, 8000, new BigDecimal("10.01"), "RUB"
        ), BigDecimal.ZERO, "CALL_BUDGET_EXCEEDED");
        assertCode(request, new LlmProviderPreflight(
                1000, 8000, new BigDecimal("1.00"), "USD"
        ), BigDecimal.ZERO, "CURRENCY_UNSUPPORTED");
        assertCode(request, accepted, new BigDecimal("91.01"),
                "DAILY_BUDGET_EXCEEDED");
        assertCode(request("x".repeat(140_000), 100), accepted,
                BigDecimal.ZERO, "REQUEST_TOO_LARGE");
    }

    private void assertCode(
            LlmProviderRequest request,
            LlmProviderPreflight preflight,
            BigDecimal spent,
            String code
    ) {
        assertThatThrownBy(() -> guard.validate(request, preflight, spent))
                .isInstanceOfSatisfying(
                        WeeklyReviewAiBudgetException.class,
                        failure -> org.assertj.core.api.Assertions.assertThat(
                                failure.code()
                        ).isEqualTo(code)
                );
    }

    private LlmProviderRequest request(String input, int maxOutputTokens) {
        return new LlmProviderRequest(
                UUID.randomUUID(),
                "YANDEX",
                "gpt://folder/yandexgpt-5.1",
                "system",
                "{\"value\":\"" + input + "\"}",
                "{}",
                new BigDecimal("0.1"),
                maxOutputTokens,
                Instant.parse("2026-08-27T12:03:00Z")
        );
    }
}
