package com.storeanalytics.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "app.livesklad.payload-limits")
public record LiveSkladPayloadLimitsProperties(
        DataSize maxResponseSize,
        long maxDocumentLength,
        long maxTokenCount,
        int maxNestingDepth,
        int maxStringLength,
        int maxNameLength,
        int maxNumberLength,
        DataSize maxRawPayloadSize,
        int maxCollectionRecords,
        int maxPositionsPerDocument
) {

    static final long MAX_CONFIGURED_RESPONSE_BYTES = 16L * 1024 * 1024;
    static final long MAX_CONFIGURED_TOKEN_COUNT = 1_000_000;
    static final int MAX_CONFIGURED_NESTING_DEPTH = 256;
    static final int MAX_CONFIGURED_STRING_LENGTH = 1024 * 1024;
    static final int MAX_CONFIGURED_NAME_LENGTH = 4096;
    static final int MAX_CONFIGURED_NUMBER_LENGTH = 1024;
    static final int MAX_CONFIGURED_COLLECTION_RECORDS = 10_000;
    static final int MAX_CONFIGURED_POSITIONS = 10_000;

    @ConstructorBinding
    public LiveSkladPayloadLimitsProperties {
        requirePositive(maxResponseSize, "maxResponseSize");
        long maxResponseBytes = maxResponseSize.toBytes();
        requireAtMost(
                maxResponseBytes,
                MAX_CONFIGURED_RESPONSE_BYTES,
                "maxResponseSize"
        );
        requirePositive(maxDocumentLength, "maxDocumentLength");
        requireAtMost(
                maxDocumentLength,
                maxResponseBytes,
                "maxDocumentLength"
        );
        requirePositive(maxTokenCount, "maxTokenCount");
        requireAtMost(maxTokenCount, MAX_CONFIGURED_TOKEN_COUNT, "maxTokenCount");
        requirePositive(maxNestingDepth, "maxNestingDepth");
        requireAtMost(
                maxNestingDepth,
                MAX_CONFIGURED_NESTING_DEPTH,
                "maxNestingDepth"
        );
        requirePositive(maxStringLength, "maxStringLength");
        requireAtMost(
                maxStringLength,
                MAX_CONFIGURED_STRING_LENGTH,
                "maxStringLength"
        );
        requirePositive(maxNameLength, "maxNameLength");
        requireAtMost(maxNameLength, MAX_CONFIGURED_NAME_LENGTH, "maxNameLength");
        requirePositive(maxNumberLength, "maxNumberLength");
        requireAtMost(
                maxNumberLength,
                MAX_CONFIGURED_NUMBER_LENGTH,
                "maxNumberLength"
        );
        requirePositive(maxRawPayloadSize, "maxRawPayloadSize");
        requireAtMost(
                maxRawPayloadSize.toBytes(),
                MAX_CONFIGURED_RESPONSE_BYTES,
                "maxRawPayloadSize"
        );
        requirePositive(maxCollectionRecords, "maxCollectionRecords");
        requireAtMost(
                maxCollectionRecords,
                MAX_CONFIGURED_COLLECTION_RECORDS,
                "maxCollectionRecords"
        );
        requirePositive(maxPositionsPerDocument, "maxPositionsPerDocument");
        requireAtMost(
                maxPositionsPerDocument,
                MAX_CONFIGURED_POSITIONS,
                "maxPositionsPerDocument"
        );
    }

    public static LiveSkladPayloadLimitsProperties defaults() {
        return new LiveSkladPayloadLimitsProperties(
                DataSize.ofMegabytes(2),
                2L * 1024 * 1024,
                100_000,
                64,
                65_536,
                256,
                128,
                DataSize.ofMegabytes(4),
                1000,
                1000
        );
    }

    public long maxResponseBytes() {
        return maxResponseSize.toBytes();
    }

    public long maxRawPayloadBytes() {
        return maxRawPayloadSize.toBytes();
    }
    private static void requirePositive(DataSize value, String name) {
        if (value == null || value.toBytes() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireAtMost(long value, long maximum, String name) {
        if (value > maximum) {
            throw new IllegalArgumentException(name + " exceeds the safety ceiling");
        }
    }
}
