package com.storeanalytics.interpretation.validation;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Period;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Snapshot;
import com.storeanalytics.interpretation.generation.LlmAnalysisAttempt;
import com.storeanalytics.interpretation.snapshot.PersistedWeeklySnapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public final class PersistedWeeklyInterpretationInputFactory {

    private static final String STORE_REF = "S01";

    private final ObjectMapper objectMapper;

    public PersistedWeeklyInterpretationInputFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public WeeklyInterpretationInput create(
            LlmAnalysisAttempt attempt,
            PersistedWeeklySnapshot snapshot
    ) {
        LlmAnalysisAttempt sourceAttempt = requireNonNull(attempt, "attempt");
        PersistedWeeklySnapshot sourceSnapshot = requireNonNull(
                snapshot, "snapshot"
        );
        if (sourceAttempt.providerInputBody() == null) {
            return create(sourceSnapshot);
        }
        require(
                sha256(sourceAttempt.providerInputBody()).equals(
                        sourceAttempt.providerInputHash()
                ),
                "Persisted provider input hash does not match its body"
        );
        WeeklyInterpretationInput input;
        try {
            input = objectMapper.readValue(
                    sourceAttempt.providerInputBody(),
                    WeeklyInterpretationInput.class
            );
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Persisted provider input cannot be decoded", exception
            );
        }
        Snapshot header = input.snapshot();
        require(input.contractVersion()
                        == sourceSnapshot.payload().contractVersion()
                        && STORE_REF.equals(header.storeRef())
                        && header.snapshotRef().equals(sourceSnapshot.id())
                        && header.revision() == sourceSnapshot.revision()
                        && header.factsHash().equals(sourceSnapshot.factsHash())
                        && header.timezone().equals(sourceSnapshot.timezone())
                        && header.period().start().equals(
                                sourceSnapshot.query().period().start()
                        )
                        && header.period().end().equals(
                                sourceSnapshot.query().period().end()
                        )
                        && header.comparisonPeriod().start().equals(
                                sourceSnapshot.query().comparisonPeriod().start()
                        )
                        && header.comparisonPeriod().end().equals(
                                sourceSnapshot.query().comparisonPeriod().end()
                        )
                        && header.qualityStatus() == sourceSnapshot.qualityStatus()
                        && header.versions().equals(sourceSnapshot.versions()),
                "Persisted provider input does not belong to the job snapshot");
        return input;
    }

    public WeeklyInterpretationInput create(PersistedWeeklySnapshot snapshot) {
        PersistedWeeklySnapshot value = requireNonNull(snapshot, "snapshot");
        Snapshot header = new Snapshot(
                value.id(),
                value.revision(),
                value.factsHash(),
                STORE_REF,
                value.timezone(),
                new Period(
                        value.query().period().start(),
                        value.query().period().end()
                ),
                new Period(
                        value.query().comparisonPeriod().start(),
                        value.query().comparisonPeriod().end()
                ),
                value.qualityStatus(),
                value.versions()
        );
        return new WeeklyInterpretationInput(
                value.payload().contractVersion(),
                header,
                value.payload().manifest(),
                value.payload().facts()
        );
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8)
            );
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable", exception
            );
        }
    }
}
