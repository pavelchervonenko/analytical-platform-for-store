package com.storeanalytics.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class LiveSkladPayloadLimitsPropertiesTest {

    @Test
    void providesBoundedProductionDefaults() {
        LiveSkladPayloadLimitsProperties properties =
                LiveSkladPayloadLimitsProperties.defaults();

        assertThat(properties.maxResponseBytes()).isEqualTo(2L * 1024 * 1024);
        assertThat(properties.maxDocumentLength())
                .isLessThanOrEqualTo(properties.maxResponseBytes());
        assertThat(properties.maxTokenCount()).isPositive();
        assertThat(properties.maxNestingDepth()).isPositive();
    }

    @Test
    void rejectsNonPositiveAndUnsafeConfiguration() {
        assertThatThrownBy(() -> properties(DataSize.ofBytes(0), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxResponseSize");
        assertThatThrownBy(() -> properties(DataSize.ofMegabytes(17), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("safety ceiling");
        assertThatThrownBy(() -> properties(DataSize.ofKilobytes(1), 2048))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDocumentLength");
    }

    private LiveSkladPayloadLimitsProperties properties(
            DataSize maxResponseSize,
            long maxDocumentLength
    ) {
        return new LiveSkladPayloadLimitsProperties(
                maxResponseSize,
                maxDocumentLength,
                100,
                10,
                100,
                100,
                100,
                DataSize.ofMegabytes(4),
                1000,
                1000
        );
    }
}
