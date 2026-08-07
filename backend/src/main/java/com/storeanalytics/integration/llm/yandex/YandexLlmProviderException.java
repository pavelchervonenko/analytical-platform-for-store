package com.storeanalytics.integration.llm.yandex;

import com.storeanalytics.interpretation.generation.LlmProviderException;
import com.storeanalytics.interpretation.generation.LlmProviderOutcome;
import java.time.Duration;

public final class YandexLlmProviderException extends LlmProviderException {

    private final LlmProviderFailureKind kind;
    private final LlmProviderOutcomeCertainty outcomeCertainty;
    private final Integer httpStatus;
    private final Duration retryAfter;

    public YandexLlmProviderException(
            LlmProviderFailureKind kind,
            LlmProviderOutcomeCertainty outcomeCertainty,
            String safeMessage,
            Integer httpStatus,
            Duration retryAfter,
            Throwable cause
    ) {
        super(safeMessage, cause);
        this.kind = java.util.Objects.requireNonNull(kind, "kind");
        this.outcomeCertainty = java.util.Objects.requireNonNull(
                outcomeCertainty,
                "outcomeCertainty"
        );
        this.httpStatus = httpStatus;
        this.retryAfter = retryAfter;
    }

    public LlmProviderFailureKind getKind() {
        return kind;
    }

    public LlmProviderOutcomeCertainty getOutcomeCertainty() {
        return outcomeCertainty;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }

    @Override
    public String failureCode() {
        return kind.name();
    }

    @Override
    public LlmProviderOutcome outcome() {
        return LlmProviderOutcome.valueOf(outcomeCertainty.name());
    }

    @Override
    public Integer httpStatus() {
        return httpStatus;
    }

    @Override
    public Duration retryAfter() {
        return retryAfter;
    }

    @Override
    public boolean isRetryable() {
        return kind == LlmProviderFailureKind.RATE_LIMITED
                || kind == LlmProviderFailureKind.TRANSIENT_PROVIDER
                || kind == LlmProviderFailureKind.DEADLINE_EXCEEDED
                || kind == LlmProviderFailureKind.TRANSPORT;
    }
}
