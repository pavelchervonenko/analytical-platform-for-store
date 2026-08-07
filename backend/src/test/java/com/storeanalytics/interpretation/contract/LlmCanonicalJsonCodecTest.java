package com.storeanalytics.interpretation.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class LlmCanonicalJsonCodecTest {

    private final LlmCanonicalJsonCodec codec = new LlmCanonicalJsonCodec(
            JsonMapper.builder().findAndAddModules().build()
    );

    @Test
    void canonicalizesObjectsWithoutReorderingArrays() {
        CanonicalLlmJson value = codec.canonicalize(
                "{\"z\":[{\"b\":2,\"a\":1},3],\"a\":true}"
        );

        assertThat(value.canonicalJson())
                .isEqualTo("{\"a\":true,\"z\":[{\"a\":1,\"b\":2},3]}");
        assertThat(codec.decodeVerified(
                "{\"z\":[{\"a\":1,\"b\":2},3],\"a\":true}",
                value.contentHash()
        )).isEqualTo(value.content());
    }

    @Test
    void rejectsPayloadWhoseCanonicalHashDoesNotMatch() {
        assertThatThrownBy(() -> codec.decodeVerified(
                "{\"store\":{}}",
                "a".repeat(64)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hash does not match");
    }
}
