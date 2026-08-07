package com.storeanalytics.interpretation.generation;

import java.time.Duration;

/**
 * Provider-neutral failure contract consumed by the durable generation workflow.
 * Implementations must expose only safe, non-secret diagnostic text.
 */
public abstract class LlmProviderException extends RuntimeException {

    protected LlmProviderException(String safeMessage, Throwable cause) {
        super(safeMessage, cause);
    }

    public abstract String failureCode();

    public abstract LlmProviderOutcome outcome();

    public abstract Integer httpStatus();

    public abstract Duration retryAfter();

    public abstract boolean isRetryable();
}
