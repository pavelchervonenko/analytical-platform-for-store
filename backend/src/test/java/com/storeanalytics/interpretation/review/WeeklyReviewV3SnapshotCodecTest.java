package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Provenance;
import com.storeanalytics.interpretation.review.WeeklyReviewV3Response.AdditionalSales;
import com.storeanalytics.interpretation.review.WeeklyReviewV3Response.Membership;
import com.storeanalytics.interpretation.review.WeeklyReviewV3Response.TeamDisplay;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WeeklyReviewV3SnapshotCodecTest {

    private final WeeklyReviewSnapshotCodec v2 = new WeeklyReviewSnapshotCodec();
    private final WeeklyReviewV3SnapshotCodec v3 = new WeeklyReviewV3SnapshotCodec();
    private final WeeklyReviewVersionedCodec versioned = new WeeklyReviewVersionedCodec(v2, v3);

    @Test
    void v3RoundTripAndSemanticHashIgnoreTechnicalTimesAndSourceIdentity() throws Exception {
        WeeklyReviewV3Response first = syntheticEnvelope("a".repeat(64), "b".repeat(64),
                Instant.parse("2026-08-24T04:00:00Z"));
        WeeklyReviewV3Response rechecked = syntheticEnvelope("c".repeat(64), "b".repeat(64),
                Instant.parse("2026-08-25T04:00:00Z"));
        WeeklyReviewV3Response changedCohort = syntheticEnvelope("a".repeat(64),
                "d".repeat(64), Instant.parse("2026-08-24T04:00:00Z"));

        assertThat(v3.deserialize(v3.serialize(first))).isEqualTo(first);
        assertThat(versioned.deserialize(3, versioned.serialize(first))).isEqualTo(first);
        assertThat(v3.contentHash(first)).isEqualTo(v3.contentHash(rechecked));
        assertThat(v3.contentHash(first)).isNotEqualTo(v3.contentHash(changedCohort));
        assertThat(versioned.contentHash(first)).isEqualTo(v3.contentHash(first));
    }

    @Test
    void dispatchDoesNotInterpretV2AsV3AndPreservesV2Hash() throws Exception {
        WeeklyReviewResponse legacy = legacy();

        assertThat(versioned.deserialize(2, versioned.serialize(legacy))).isEqualTo(legacy);
        assertThat(versioned.contentHash(legacy)).isEqualTo(
                "edc8467c6773771be7d3b01b3631f86087619f2eee146b3ebb21c0671ef15072");
        assertThatThrownBy(() -> versioned.deserialize(3, v2.serialize(legacy)))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> versioned.deserialize(4, v2.serialize(legacy)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void v3RejectsInvalidSellerMembershipIdentity() {
        assertThatThrownBy(() -> new Membership("CURRENT_RANKING_AT_GENERATION",
                "a".repeat(64), "b".repeat(64), "c".repeat(64),
                Instant.parse("2026-08-24T04:00:00Z"), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void v3RejectsStoreIdentifiersHiddenInsideNestedBlocks() throws Exception {
        WeeklyReviewV3Response contaminated = syntheticEnvelope("a".repeat(64),
                "b".repeat(64), Instant.parse("2026-08-24T04:00:00Z"), legacy());

        assertThatThrownBy(() -> v3.serialize(contaminated))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("legacy STORE identifier");
    }

    @Test
    void v3ReadRejectsCaseVariantStoreStructureReference() throws Exception {
        WeeklyReviewV3Response valid = syntheticEnvelope("a".repeat(64), "b".repeat(64),
                Instant.parse("2026-08-24T04:00:00Z"));
        String encoded = v3.serialize(valid);
        String contaminated = encoded.replace("SELLERS.STRUCTURE", "store.structure");
        assertThat(contaminated).isNotEqualTo(encoded);

        assertThatThrownBy(() -> v3.deserialize(contaminated))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("legacy STORE identifier");
    }

    private WeeklyReviewV3Response syntheticEnvelope(
            String identityHash,
            String cohortHash,
            Instant checkedAt
    ) throws Exception {
        return syntheticEnvelope(identityHash, cohortHash, checkedAt, sellerShaped());
    }

    private WeeklyReviewV3Response syntheticEnvelope(
            String identityHash,
            String cohortHash,
            Instant checkedAt,
            WeeklyReviewResponse legacy
    ) {
        BigDecimal zero = BigDecimal.ZERO;
        return new WeeklyReviewV3Response(3, legacy.versions(), legacy.period(),
                new Provenance(UUID.randomUUID().toString(), 1, checkedAt, null, false, null),
                legacy.reportState(), legacy.qualitySummary(), legacy.sourceCoverage().stream()
                        .map(WeeklyReviewV3Response.SellerSourceCoverage::from).toList(),
                "SELLERS", new Membership("CURRENT_RANKING_AT_GENERATION", cohortHash,
                        cohortHash, "e".repeat(64), checkedAt, 0), identityHash,
                legacy.summary(), legacy.results(), legacy.revenueDecomposition(),
                new AdditionalSales(legacy.results().get(0), legacy.results().get(1),
                        zero, zero, null, null, zero, false),
                legacy.factors(), legacy.salesStructure(), legacy.team(),
                new TeamDisplay(0, 0, zero, zero, zero, zero),
                List.of(), List.of(), List.of(), List.of(), legacy.aiEnhancement());
    }

    private WeeklyReviewResponse sellerShaped() throws Exception {
        String syntheticPayload = v2.serialize(legacy())
                .replace("STORE.", "SELLERS.")
                .replace("store:", "sellers:")
                .replace("\"STORE\"", "\"SELLERS\"");
        return v2.deserialize(syntheticPayload);
    }

    private WeeklyReviewResponse legacy() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle.kts"))) {
            root = root.getParent();
        }
        assertThat(root).isNotNull();
        return v2.deserialize(Files.readString(root.resolve(
                "frontend/src/test/fixtures/weekly-review-v2-ready.json")));
    }
}
