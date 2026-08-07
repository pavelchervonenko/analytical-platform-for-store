package com.storeanalytics.interpretation.generation;

public enum LlmProviderPreflightFailureKind {
    REQUEST_TOO_LARGE,
    CONTEXT_WINDOW_EXCEEDED,
    CURRENCY_UNSUPPORTED,
    COST_BUDGET_EXCEEDED
}
