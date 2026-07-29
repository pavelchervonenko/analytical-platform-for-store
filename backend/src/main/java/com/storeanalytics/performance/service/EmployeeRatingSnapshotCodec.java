package com.storeanalytics.performance.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.storeanalytics.performance.model.EmployeeRatingSnapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
class EmployeeRatingSnapshotCodec {

    private final ObjectMapper objectMapper;

    EmployeeRatingSnapshotCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String encode(EmployeeRatingResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JacksonException exception) {
            throw new IllegalStateException("employee rating snapshot could not be created");
        }
    }

    String sha256(String payload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("employee rating snapshot hash is unavailable");
        }
    }

    EmployeeRatingResult decode(EmployeeRatingSnapshot snapshot) {
        String payload = snapshot.getResultPayload();
        if (!MessageDigest.isEqual(
                snapshot.getResultSha256().getBytes(StandardCharsets.US_ASCII),
                sha256(payload).getBytes(StandardCharsets.US_ASCII)
        )) {
            throw corrupted();
        }
        try {
            EmployeeRatingResult result = objectMapper.readValue(
                    payload, EmployeeRatingResult.class
            );
            if (!result.storeId().equals(snapshot.getStore().getId())
                    || !result.periodStart().equals(snapshot.getPeriodStart())
                    || !result.periodEnd().equals(snapshot.getPeriodEnd())
                    || !result.formula().version().equals(snapshot.getFormulaCode())) {
                throw corrupted();
            }
            return result.withHistory(EmployeeRatingHistoryView.finalized(
                    snapshot.getId(),
                    snapshot.getCreatedAt(),
                    snapshot.getFinalizedBy().getId(),
                    snapshot.getFinalizedByName()
            ));
        } catch (JacksonException | NullPointerException exception) {
            throw corrupted();
        }
    }

    private IllegalStateException corrupted() {
        return new IllegalStateException("employee rating snapshot integrity check failed");
    }
}
