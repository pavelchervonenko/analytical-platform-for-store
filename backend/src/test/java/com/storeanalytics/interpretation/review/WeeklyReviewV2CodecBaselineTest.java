package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Pins the old content identity before introducing a seller-scoped report contract. */
class WeeklyReviewV2CodecBaselineTest {

    @Test
    void existingGoldenReportKeepsItsContentHashAndRoundTrips() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle.kts"))) {
            root = root.getParent();
        }
        assertThat(root).isNotNull();
        String payload = Files.readString(root.resolve(
                "frontend/src/test/fixtures/weekly-review-v2-ready.json"));
        WeeklyReviewSnapshotCodec codec = new WeeklyReviewSnapshotCodec();
        WeeklyReviewResponse response = codec.deserialize(payload);

        assertThat(response.contractVersion()).isEqualTo(2);
        assertThat(codec.contentHash(response))
                .isEqualTo("edc8467c6773771be7d3b01b3631f86087619f2eee146b3ebb21c0671ef15072");
        assertThat(codec.deserialize(codec.serialize(response))).isEqualTo(response);
    }
}
