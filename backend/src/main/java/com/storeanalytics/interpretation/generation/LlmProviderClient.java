package com.storeanalytics.interpretation.generation;

public interface LlmProviderClient {

    String providerCode();

    LlmProviderPreflight preflight(LlmProviderRequest request);

    LlmProviderResponseReceipt generate(LlmProviderRequest request);
}
