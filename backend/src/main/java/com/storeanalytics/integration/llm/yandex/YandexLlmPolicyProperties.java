package com.storeanalytics.integration.llm.yandex;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.llm.yandex-policy")
public record YandexLlmPolicyProperties(
        @DefaultValue("32768") int contextWindowTokens,
        @DefaultValue("1048576") int maxResponseBytes,
        @DefaultValue("0.8") BigDecimal inputRubPerThousandTokens,
        @DefaultValue("0.8") BigDecimal cachedInputRubPerThousandTokens,
        @DefaultValue("0.8") BigDecimal outputRubPerThousandTokens
) {

    public YandexLlmPolicyProperties {
        require(contextWindowTokens >= 4096 && contextWindowTokens <= 1_000_000,
                "Yandex contextWindowTokens must be between 4096 and 1000000");
        require(maxResponseBytes >= 16_384 && maxResponseBytes <= 1_048_576,
                "Yandex maxResponseBytes must be between 16384 and 1048576");
        inputRubPerThousandTokens = price(
                inputRubPerThousandTokens,
                "inputRubPerThousandTokens"
        );
        cachedInputRubPerThousandTokens = price(
                cachedInputRubPerThousandTokens,
                "cachedInputRubPerThousandTokens"
        );
        outputRubPerThousandTokens = price(
                outputRubPerThousandTokens,
                "outputRubPerThousandTokens"
        );
    }

    private static BigDecimal price(BigDecimal value, String field) {
        BigDecimal checked = requireNonNull(value, field);
        require(checked.signum() >= 0, "Yandex " + field + " must not be negative");
        require(checked.compareTo(new BigDecimal("1000")) <= 0,
                "Yandex " + field + " must not exceed 1000");
        return checked;
    }
}
