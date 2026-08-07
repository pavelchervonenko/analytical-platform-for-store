package com.storeanalytics.interpretation.generation;

/**
 * Whether a failed provider call is known to have reached the provider.
 */
public enum LlmProviderOutcome {
    NOT_SENT,
    UNKNOWN,
    RESPONSE_RECEIVED
}
