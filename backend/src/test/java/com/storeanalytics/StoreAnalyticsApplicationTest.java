package com.storeanalytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class StoreAnalyticsApplicationTest {

    @Test
    void printsPackagedSchemaVersionWithoutStartingSpring() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        boolean printed = StoreAnalyticsApplication.printExpectedSchemaVersion(
                new String[]{"--print-expected-schema-version"},
                new PrintStream(bytes, true, StandardCharsets.UTF_8)
        );

        assertThat(printed).isTrue();
        assertThat(bytes.toString(StandardCharsets.UTF_8).trim()).isEqualTo("48");
    }
}
