package com.storeanalytics.sync.support;

public record PreparedRawPayload(
        String json,
        String sha256,
        int sizeBytes
) {
}
