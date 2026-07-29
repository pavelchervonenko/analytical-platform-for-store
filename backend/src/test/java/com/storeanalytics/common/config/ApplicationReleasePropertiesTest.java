package com.storeanalytics.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class ApplicationReleasePropertiesTest {

    @Test
    void acceptsValidatedReleaseIdentity() {
        String digest = "sha256:" + "a".repeat(64);

        ApplicationReleaseProperties properties =
                new ApplicationReleaseProperties(
                        "release-2026.07.25",
                        digest
                );

        assertThat(properties.id()).isEqualTo("release-2026.07.25");
        assertThat(properties.imageDigest()).isEqualTo(digest);
    }

    @Test
    void normalizesMissingReleaseIdentity() {
        ApplicationReleaseProperties properties =
                new ApplicationReleaseProperties(null, "   ");

        assertThat(properties.id()).isEmpty();
        assertThat(properties.imageDigest()).isEmpty();
    }

    @Test
    void rejectsUnsafeReleaseId() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ApplicationReleaseProperties(
                        "release with spaces",
                        null
                ));
    }

    @Test
    void rejectsMalformedImageDigest() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ApplicationReleaseProperties(
                        null,
                        "sha256:not-a-digest"
                ));
    }
}
