package com.storeanalytics.report.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.storeanalytics.metrics.model.ReportSnapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
class ReportSnapshotCodec {

    private final ObjectMapper objectMapper;

    ReportSnapshotCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    EncodedReport encode(Object value) {
        String payload = serialize(value);
        return new EncodedReport(payload, sha256(payload));
    }

    String sourceHash(Object source) {
        return sha256(serialize(source));
    }

    MonthlyReportPayload decodeMonthly(ReportSnapshot snapshot) {
        verify(snapshot);
        return deserialize(snapshot.getPayload(), MonthlyReportPayload.class);
    }

    AnnualReportPayload decodeAnnual(ReportSnapshot snapshot) {
        verify(snapshot);
        return deserialize(snapshot.getPayload(), AnnualReportPayload.class);
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("report snapshot could not be created", exception);
        }
    }

    private <T> T deserialize(String payload, Class<T> type) {
        try {
            return objectMapper.readValue(payload, type);
        } catch (JacksonException exception) {
            throw corrupted(exception);
        }
    }

    private void verify(ReportSnapshot snapshot) {
        String actual = sha256(snapshot.getPayload());
        if (!MessageDigest.isEqual(
                snapshot.getPayloadHash().getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII)
        )) {
            throw corrupted(null);
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("report SHA-256 is unavailable", exception);
        }
    }

    private IllegalStateException corrupted(Exception cause) {
        return new IllegalStateException("report snapshot integrity check failed", cause);
    }
}
