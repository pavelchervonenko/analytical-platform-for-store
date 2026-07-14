package com.storeanalytics.integration.livesklad;

import com.storeanalytics.common.config.LiveSkladProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Service
public class HttpLiveSkladClient implements LiveSkladClient {

    private final RestClient restClient;

    public HttpLiveSkladClient(RestClient.Builder builder, LiveSkladProperties properties) {
        if (!StringUtils.hasText(properties.baseUrl())) {
            this.restClient = null;
            return;
        }

        RestClient.Builder configuredBuilder = builder.baseUrl(stripTrailingSlash(properties.baseUrl()));
        if (StringUtils.hasText(properties.apiToken())) {
            configuredBuilder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiToken());
        }
        this.restClient = configuredBuilder.build();
    }

    @Override
    public String getRaw(String path) {
        if (restClient == null) {
            throw new IllegalStateException("LiveSklad API base URL is not configured");
        }
        return restClient.get()
                .uri(normalizePath(path))
                .retrieve()
                .body(String.class);
    }

    private String normalizePath(String path) {
        if (!StringUtils.hasText(path)) {
            throw new IllegalArgumentException("LiveSklad API path must not be blank");
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private String stripTrailingSlash(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
