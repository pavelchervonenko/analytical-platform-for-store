package com.storeanalytics.common.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class LiveSkladPropertiesSecurityTest {

    @Test
    void acceptsHttpsBaseUrlFromAllowlist() {
        assertThatCode(() -> properties(
                "https://api.example.test",
                List.of("api.example.test"),
                true
        )).doesNotThrowAnyException();
    }

    @Test
    void rejectsInsecureOrUnapprovedProductionBaseUrl() {
        assertThatThrownBy(() -> properties(
                "http://api.example.test",
                List.of("api.example.test"),
                true
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");

        assertThatThrownBy(() -> properties(
                "https://internal.example.test",
                List.of("api.example.test"),
                true
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    void rejectsUserInfoQueryAndNonHttpSchemes() {
        assertThatThrownBy(() -> properties(
                "https://user@example.test",
                List.of(),
                false
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(
                "file:///etc/passwd",
                List.of(),
                false
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private LiveSkladProperties properties(
            String baseUrl,
            List<String> allowedHosts,
            boolean requireHttps
    ) {
        return new LiveSkladProperties(
                baseUrl,
                "login",
                "password",
                allowedHosts,
                requireHttps,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)
        );
    }
}
