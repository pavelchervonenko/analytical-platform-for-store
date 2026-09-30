package com.storeanalytics.metrics.service;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Internal immutable current roster; not a reconstruction of historical membership. */
public record SellerCohortSnapshot(UUID storeId, List<UUID> employeeIds) {

    public static final String BASIS = "CURRENT_RANKING_AT_GENERATION";

    public SellerCohortSnapshot {
        requireNonNull(storeId, "storeId");
        employeeIds = List.copyOf(requireNonNull(employeeIds, "employeeIds"))
                .stream().distinct().sorted().toList();
    }

    public String fingerprint() {
        String identity = BASIS + "\n" + storeId + "\n"
                + String.join("\n", employeeIds.stream().map(UUID::toString).toList());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
