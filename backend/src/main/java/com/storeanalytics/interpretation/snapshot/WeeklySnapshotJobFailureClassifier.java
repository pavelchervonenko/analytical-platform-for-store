package com.storeanalytics.interpretation.snapshot;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;

@Component
public final class WeeklySnapshotJobFailureClassifier {

    public WeeklySnapshotJobFailure classify(RuntimeException exception) {
        RuntimeException failure = requireNonNull(exception, "exception");
        if (contains(failure, WeeklySnapshotJobCancellationException.class)) {
            return failure(false, "SNAPSHOT_CANCELLED");
        }
        if (contains(failure, TransientDataAccessException.class)) {
            return failure(true, "TRANSIENT_DATABASE");
        }
        if (contains(failure, WeeklySnapshotIntegrityException.class)) {
            return failure(false, "SNAPSHOT_INTEGRITY");
        }
        if (containsMessage(failure, "evidence exceeds schema limit")) {
            return failure(false, "SNAPSHOT_EVIDENCE_LIMIT");
        }
        if (containsMessage(failure, "facts exceed schema limit")) {
            return failure(false, "SNAPSHOT_FACT_LIMIT");
        }
        if (containsMessage(failure, "employees exceed schema limit")
                || containsMessage(failure, "supports at most")) {
            return failure(false, "SNAPSHOT_EMPLOYEE_LIMIT");
        }
        if (containsMessage(failure, "Conflicting evidence entry")) {
            return failure(false, "SNAPSHOT_EVIDENCE_CONFLICT");
        }
        if (containsMessage(failure, "Conflicting display names")) {
            return failure(false, "SNAPSHOT_IDENTITY_CONFLICT");
        }
        if (contains(failure, IllegalArgumentException.class)) {
            return failure(false, "SNAPSHOT_CONTRACT");
        }
        if (contains(failure, IllegalStateException.class)) {
            return failure(false, "SNAPSHOT_STATE");
        }
        return failure(false, "SNAPSHOT_EXECUTION");
    }

    private WeeklySnapshotJobFailure failure(boolean retryable, String code) {
        return new WeeklySnapshotJobFailure(
                retryable,
                code,
                "Weekly snapshot execution failed: " + code
        );
    }

    private boolean containsMessage(Throwable failure, String fragment) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof IllegalArgumentException
                    && current.getMessage() != null
                    && current.getMessage().contains(fragment)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean contains(Throwable failure, Class<? extends Throwable> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
