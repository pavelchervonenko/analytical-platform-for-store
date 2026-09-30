package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import org.springframework.stereotype.Component;

/** Explicit dispatch by trusted DB contract version before payload deserialization. */
@Component
final class WeeklyReviewVersionedCodec {

    private final WeeklyReviewSnapshotCodec v2;
    private final WeeklyReviewV3SnapshotCodec v3;

    WeeklyReviewVersionedCodec(
            WeeklyReviewSnapshotCodec v2,
            WeeklyReviewV3SnapshotCodec v3
    ) {
        this.v2 = v2;
        this.v3 = v3;
    }

    WeeklyReviewContract deserialize(int contractVersion, String payload) {
        return switch (contractVersion) {
            case 2 -> v2.deserialize(payload);
            case 3 -> v3.deserialize(payload);
            default -> throw new IllegalArgumentException(
                    "Unsupported weekly review contract version: " + contractVersion);
        };
    }

    String serialize(WeeklyReviewContract response) {
        WeeklyReviewContract contract = requireNonNull(response, "response");
        if (contract instanceof WeeklyReviewResponse legacy) {
            return v2.serialize(legacy);
        }
        return v3.serialize((WeeklyReviewV3Response) contract);
    }

    String contentHash(WeeklyReviewContract response) {
        WeeklyReviewContract contract = requireNonNull(response, "response");
        if (contract instanceof WeeklyReviewResponse legacy) {
            return v2.contentHash(legacy);
        }
        return v3.contentHash((WeeklyReviewV3Response) contract);
    }
}
