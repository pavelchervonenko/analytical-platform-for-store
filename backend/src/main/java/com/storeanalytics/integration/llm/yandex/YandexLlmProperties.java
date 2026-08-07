package com.storeanalytics.integration.llm.yandex;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.llm.yandex")
public final class YandexLlmProperties {

    private final String folderId;
    private final String apiKey;
    private final String modelUri;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    public YandexLlmProperties(
            @DefaultValue("") String folderId,
            @DefaultValue("") String apiKey,
            @DefaultValue("") String modelUri,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("90s") Duration readTimeout
    ) {
        this.folderId = Objects.requireNonNull(folderId, "folderId");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.modelUri = Objects.requireNonNull(modelUri, "modelUri");
        this.connectTimeout = requirePositive(connectTimeout, "connectTimeout");
        this.readTimeout = requirePositive(readTimeout, "readTimeout");
    }

    public String getFolderId() {
        return folderId;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getModelUri() {
        return modelUri;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public boolean isConfigured() {
        return !folderId.isBlank() && !apiKey.isBlank() && !modelUri.isBlank();
    }

    public boolean isReadyForGeneration() {
        return isConfigured()
                && safeHeaderValue(folderId)
                && safeHeaderValue(apiKey)
                && modelUri.startsWith("gpt://" + folderId + "/")
                && !modelUri.endsWith("/latest");
    }

    @Override
    public String toString() {
        return "YandexLlmProperties[folderIdConfigured=" + !folderId.isBlank()
                + ", apiKey=REDACTED, modelUriConfigured=" + !modelUri.isBlank()
                + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + "]";
    }

    private static boolean safeHeaderValue(String value) {
        return value.indexOf('\r') < 0 && value.indexOf('\n') < 0;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("LLM " + name + " must be positive");
        }
        return value;
    }
}
