package com.storeanalytics.sync.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.storeanalytics.common.config.LiveSkladPayloadLimitsProperties;
import com.storeanalytics.integration.livesklad.exception.LiveSkladPayloadRejectedException;
import com.storeanalytics.integration.livesklad.exception.LiveSkladPayloadRejectedException.Reason;
import com.storeanalytics.integration.livesklad.observability.LiveSkladPayloadRejectionMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class JsonPayloadHasherTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void preparesRawJsonOnceAndKeepsCanonicalHashStable() {
        JsonPayloadHasher hasher = new JsonPayloadHasher(
                objectMapper,
                limits(DataSize.ofKilobytes(1))
        );
        ObjectNode first = objectMapper.createObjectNode();
        first.put("name", "North");
        first.put("id", "store-1");
        ObjectNode second = objectMapper.createObjectNode();
        second.put("id", "store-1");
        second.put("name", "North");

        PreparedRawPayload firstPrepared = hasher.prepare(RawPayloadProfile.STORE, first);
        PreparedRawPayload secondPrepared = hasher.prepare(RawPayloadProfile.STORE, second);

        assertThat(firstPrepared.sha256()).isEqualTo(secondPrepared.sha256());
        assertThat(firstPrepared.json()).contains("\"id\":\"store-1\"");
        assertThat(firstPrepared.json()).contains("\"name\":\"North\"");
        assertThat(firstPrepared.sizeBytes()).isEqualTo(
                firstPrepared.json().getBytes(StandardCharsets.UTF_8).length
        );
    }

    @Test
    void unknownVendorFieldsNeitherPersistNorChangeTheRetainedHash() {
        JsonPayloadHasher hasher = new JsonPayloadHasher(
                objectMapper,
                limits(DataSize.ofKilobytes(1))
        );
        ObjectNode first = objectMapper.createObjectNode();
        first.put("id", "store-1");
        first.put("name", "North");
        first.put("accessToken", "first-secret");
        ObjectNode second = first.deepCopy();
        second.put("accessToken", "second-secret");
        second.put("ownerEmail", "private@example.com");

        PreparedRawPayload firstPrepared = hasher.prepare(
                RawPayloadProfile.STORE,
                first
        );
        PreparedRawPayload secondPrepared = hasher.prepare(
                RawPayloadProfile.STORE,
                second
        );

        assertThat(secondPrepared.sha256()).isEqualTo(firstPrepared.sha256());
        assertThat(secondPrepared.json())
                .isEqualTo(firstPrepared.json())
                .doesNotContain(
                        "accessToken",
                        "first-secret",
                        "second-secret",
                        "ownerEmail",
                        "private@example.com"
                );
    }

    @Test
    void rejectsRawJsonBeforePersistenceWhenItExceedsLimit() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        JsonPayloadHasher hasher = new JsonPayloadHasher(
                objectMapper,
                limits(DataSize.ofBytes(32)),
                new LiveSkladPayloadRejectionMetrics(registry)
        );
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("address", "x".repeat(64));
        payload.put("sensitive", "must-not-appear");

        assertThatThrownBy(() -> hasher.prepare(RawPayloadProfile.STORE, payload))
                .isInstanceOf(LiveSkladPayloadRejectedException.class)
                .satisfies(failure -> {
                    LiveSkladPayloadRejectedException rejected =
                            (LiveSkladPayloadRejectedException) failure;
                    assertThat(rejected.getReason())
                            .isEqualTo(Reason.RAW_PAYLOAD_TOO_LARGE);
                    assertThat(rejected.getMessage())
                            .doesNotContain("must-not-appear");
                });
        assertThat(registry.get(
                LiveSkladPayloadRejectionMetrics.REJECTIONS_METRIC
        ).tag("reason", "raw_payload_too_large").counter().count())
                .isEqualTo(1.0);
    }

    private LiveSkladPayloadLimitsProperties limits(DataSize rawLimit) {
        return new LiveSkladPayloadLimitsProperties(
                DataSize.ofMegabytes(2),
                2L * 1024 * 1024,
                100_000,
                64,
                65_536,
                256,
                128,
                rawLimit,
                1000,
                1000
        );
    }
}
