package com.storeanalytics.interpretation.generation;

public class LlmProviderPreflightException extends RuntimeException {

    private final LlmProviderPreflightFailureKind kind;

    public LlmProviderPreflightException(
            LlmProviderPreflightFailureKind kind,
            String message
    ) {
        super(message);
        this.kind = java.util.Objects.requireNonNull(kind, "kind");
    }

    public LlmProviderPreflightFailureKind getKind() {
        return kind;
    }
}
