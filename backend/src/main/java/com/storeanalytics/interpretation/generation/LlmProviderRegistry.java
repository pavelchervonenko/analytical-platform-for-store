package com.storeanalytics.interpretation.generation;

import static com.storeanalytics.common.validation.ModelValidation.requireText;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class LlmProviderRegistry {

    private final Map<String, LlmProviderClient> providers;

    public LlmProviderRegistry(List<LlmProviderClient> clients) {
        Map<String, LlmProviderClient> indexed = new HashMap<>();
        for (LlmProviderClient client : List.copyOf(clients)) {
            String code = requireText(client.providerCode(), "providerCode");
            LlmProviderClient previous = indexed.putIfAbsent(code, client);
            if (previous != null) {
                throw new IllegalStateException("Duplicate LLM provider client: " + code);
            }
        }
        this.providers = Map.copyOf(indexed);
    }

    public LlmProviderClient requireProvider(String providerCode) {
        String code = requireText(providerCode, "providerCode");
        LlmProviderClient client = providers.get(code);
        if (client == null) {
            throw new IllegalStateException("LLM provider is not configured: " + code);
        }
        return client;
    }
}
