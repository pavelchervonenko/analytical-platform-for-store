package com.storeanalytics.interpretation.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput;
import com.storeanalytics.interpretation.generation.LlmAnalysisAttempt;
import com.storeanalytics.interpretation.snapshot.PersistedWeeklySnapshot;
import com.storeanalytics.interpretation.snapshot.WeeklyAnalyticsFactsQuery;
import com.storeanalytics.interpretation.snapshot.WeeklySnapshotPayload;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class PersistedWeeklyInterpretationInputFactoryTest {

    private static final Instant NOW = Instant.parse("2026-08-03T05:00:00Z");

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .build();
    private final PersistedWeeklyInterpretationInputFactory factory =
            new PersistedWeeklyInterpretationInputFactory(objectMapper);

    @Test
    void recreatesValidationInputFromExactPersistedProviderProjection()
            throws IOException {
        WeeklyInterpretationInput full = input();
        WeeklyInterpretationInput exact = new WeeklyInterpretationInput(
                full.contractVersion(),
                full.snapshot(),
                full.manifest(),
                new WeeklyInterpretationInput.Facts(
                        List.of(),
                        full.facts().team(),
                        full.facts().employees(),
                        full.facts().candidateSignals()
                )
        );
        String body = objectMapper.writeValueAsString(exact);
        LlmAnalysisAttempt attempt = attempt(body, sha256(body));

        WeeklyInterpretationInput result = factory.create(
                attempt, snapshot(full)
        );

        assertThat(result).isEqualTo(exact);
        assertThat(result.facts().store()).isEmpty();
        assertThat(full.facts().store()).isNotEmpty();
    }

    @Test
    void rejectsProviderInputWhosePersistedHashDoesNotMatch() throws IOException {
        WeeklyInterpretationInput full = input();
        String body = objectMapper.writeValueAsString(full);

        assertThatThrownBy(() -> factory.create(
                attempt(body, "b".repeat(64)),
                snapshot(full)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hash does not match");
    }

    @Test
    void keepsLegacyAttemptFallbackForRowsCreatedBeforeProviderInputPersistence()
            throws IOException {
        WeeklyInterpretationInput full = input();
        LlmAnalysisAttempt attempt = mock(LlmAnalysisAttempt.class);
        when(attempt.providerInputBody()).thenReturn(null);

        WeeklyInterpretationInput result = factory.create(
                attempt, snapshot(full)
        );

        assertThat(result.facts()).isEqualTo(full.facts());
        assertThat(result.manifest()).isEqualTo(full.manifest());
    }

    private LlmAnalysisAttempt attempt(String body, String hash) {
        LlmAnalysisAttempt attempt = mock(LlmAnalysisAttempt.class);
        when(attempt.providerInputBody()).thenReturn(body);
        when(attempt.providerInputHash()).thenReturn(hash);
        return attempt;
    }

    private WeeklyInterpretationInput input() throws IOException {
        return objectMapper.readValue(
                resource(
                        "contracts/llm/examples/"
                                + "weekly-interpretation-input-v1-minimal.json"
                ),
                WeeklyInterpretationInput.class
        );
    }

    private PersistedWeeklySnapshot snapshot(WeeklyInterpretationInput input) {
        WeeklyInterpretationInput.Snapshot header = input.snapshot();
        UUID storeId = UUID.fromString(
                "00000000-0000-4000-8000-000000000100"
        );
        WeeklyAnalyticsFactsQuery query = new WeeklyAnalyticsFactsQuery(
                storeId,
                new StoreKpiPeriod(
                        header.period().start(), header.period().end()
                ),
                new StoreKpiPeriod(
                        header.comparisonPeriod().start(),
                        header.comparisonPeriod().end()
                )
        );
        return new PersistedWeeklySnapshot(
                header.snapshotRef(),
                storeId,
                query,
                header.timezone(),
                header.revision(),
                null,
                "INITIAL",
                null,
                UUID.randomUUID(),
                NOW.minusSeconds(60),
                NOW.minusSeconds(60),
                header.qualityStatus(),
                header.versions(),
                new WeeklySnapshotPayload(
                        input.contractVersion(),
                        input.manifest(),
                        input.facts()
                ),
                header.factsHash(),
                List.of(),
                NOW
        );
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("Missing resource: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8)
            );
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
