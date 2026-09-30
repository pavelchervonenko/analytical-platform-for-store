package com.storeanalytics.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.product.model.CatalogCompatibilityEvidence.Coverage;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.IdentityType;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.Subject;
import com.storeanalytics.product.model.ProductSourceKind;
import com.storeanalytics.product.service.CatalogCompatibilityRecords.Observation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CatalogLegacyCompatibilityEvidenceTest {
    private static final String ROW = """
            {"source_kind":"PRODUCT","code":"123","expected_name":"Synthetic watch charger",
             "expected_group":"/Synthetic group ","confirmed_compatibility":["APPLE_WATCH"]}
            """;

    @Test
    void bindsExactCardAndPreservesUnprovenCoverageWithoutInventingApprovalDate() throws Exception {
        byte[] bytes = journal(ROW);
        var verified = CatalogLegacyCompatibilityEvidence.verify(bytes, hash(bytes), "PRODUCT:123");
        verified.requireMatching(observation("123", "Synthetic watch charger", "/Synthetic group "));
        assertThat(verified.request().coverage()).isEqualTo(Coverage.UNDETERMINED);
        assertThat(verified.request().evidenceSha256()).isEqualTo(hash(bytes));
        assertThatThrownBy(() -> verified.requireMatching(observation("124", "Synthetic watch charger",
                "/Synthetic group "))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> verified.requireMatching(observation("123", "Renamed", "/Synthetic group ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> verified.requireMatching(observation("123", "Synthetic watch charger",
                "/Synthetic group"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAlteredArtifactMissingIdentityAndDuplicateIdentities() throws Exception {
        byte[] bytes = journal(ROW);
        assertThatThrownBy(() -> CatalogLegacyCompatibilityEvidence.verify(bytes, "0".repeat(64), "PRODUCT:123"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CatalogLegacyCompatibilityEvidence.verify(bytes, hash(bytes), "PRODUCT:456"))
                .isInstanceOf(IllegalArgumentException.class);
        byte[] duplicate = journal(ROW + "," + ROW);
        assertThatThrownBy(() -> CatalogLegacyCompatibilityEvidence.verify(duplicate, hash(duplicate), "PRODUCT:123"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "[\"WATCH_TYPO\"]", "[\"APPLE_WATCH\",\"APPLE_WATCH\"]", "null", "\"APPLE_WATCH\""})
    void rejectsAbsentOrMalformedExplicitTargets(String targetJson) throws Exception {
        byte[] bytes = journal(ROW.replace("[\"APPLE_WATCH\"]", targetJson));
        assertThatThrownBy(() -> CatalogLegacyCompatibilityEvidence.verify(bytes, hash(bytes), "PRODUCT:123"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doesNotInterpretMultipleConfirmedTargetsAsExclusiveOrUniversal() throws Exception {
        byte[] bytes = journal(ROW.replace("[\"APPLE_WATCH\"]", "[\"APPLE_WATCH\",\"IPHONE\"]"));
        assertThat(CatalogLegacyCompatibilityEvidence.verify(bytes, hash(bytes), "PRODUCT:123").request().coverage())
                .isEqualTo(Coverage.UNDETERMINED);
        byte[] universal = journal(ROW.replace("APPLE_WATCH", "PHONE_UNIVERSAL"));
        assertThat(CatalogLegacyCompatibilityEvidence.verify(universal, hash(universal), "PRODUCT:123")
                .request().coverage()).isEqualTo(Coverage.UNIVERSAL_PHONE);
    }

    @Test
    void rejectsDuplicateJsonFieldsRatherThanSilentlyTakingLastValue() throws Exception {
        byte[] bytes = journal(ROW.replace("\"code\":\"123\"", "\"code\":\"456\",\"code\":\"123\""));
        assertThatThrownBy(() -> CatalogLegacyCompatibilityEvidence.verify(bytes, hash(bytes), "PRODUCT:123"))
                .isInstanceOf(RuntimeException.class);
    }

    private static byte[] journal(String rows) {
        return ("{\"format_version\":1,\"mode\":\"OWNER_CONFIRMED_NOT_APPLIED\","
                + "\"connection_key\":\"synthetic\",\"decisions\":[" + rows + "]}").getBytes(StandardCharsets.UTF_8);
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private Observation observation(String code, String name, String group) {
        return new Observation(UUID.randomUUID(), UUID.randomUUID(),
                new Subject("synthetic", ProductSourceKind.PRODUCT, IdentityType.EXTERNAL_ID, "provider-id"),
                code, name, group);
    }
}
