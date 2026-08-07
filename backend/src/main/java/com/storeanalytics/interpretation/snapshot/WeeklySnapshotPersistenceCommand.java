package com.storeanalytics.interpretation.snapshot;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import java.time.Instant;
import java.util.UUID;

public record WeeklySnapshotPersistenceCommand(
        WeeklySnapshotDraft draft,
        UUID sourceSyncJobId,
        Instant sourceSyncCompletedAt,
        Instant sourceDataCutoff,
        WeeklySnapshotRevisionReason revisionReason,
        String revisionNote
) {

    private static final int MAX_NOTE_LENGTH = 500;

    public WeeklySnapshotPersistenceCommand {
        requireNonNull(draft, "draft");
        requireNonNull(sourceSyncJobId, "sourceSyncJobId");
        requireNonNull(sourceSyncCompletedAt, "sourceSyncCompletedAt");
        requireNonNull(sourceDataCutoff, "sourceDataCutoff");
        require(!sourceDataCutoff.isBefore(sourceSyncCompletedAt),
                "sourceDataCutoff must not precede sourceSyncCompletedAt");
        requireNonNull(revisionReason, "revisionReason");
        revisionNote = normalizeNote(revisionNote);
    }

    private static String normalizeNote(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        require(normalized.length() <= MAX_NOTE_LENGTH,
                "revisionNote must not exceed " + MAX_NOTE_LENGTH + " characters");
        return normalized;
    }
}
