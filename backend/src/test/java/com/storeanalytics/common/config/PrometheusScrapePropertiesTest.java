package com.storeanalytics.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class PrometheusScrapePropertiesTest {

    @Test
    void keepsPrometheusEndpointDisabledWithoutToken() {
        assertThat(new PrometheusScrapeProperties(null).configured()).isFalse();
        assertThat(new PrometheusScrapeProperties("   ").configured()).isFalse();
    }

    @Test
    void acceptsStrongToken() {
        PrometheusScrapeProperties properties =
                new PrometheusScrapeProperties("a".repeat(32));

        assertThat(properties.configured()).isTrue();
        assertThat(properties.token()).hasSize(32);
    }

    @Test
    void rejectsShortToken() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PrometheusScrapeProperties("too-short"));
    }

    @Test
    void rejectsWhitespaceInsideToken() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PrometheusScrapeProperties(
                        "a".repeat(31) + " " + "b"
                ));
    }
}
