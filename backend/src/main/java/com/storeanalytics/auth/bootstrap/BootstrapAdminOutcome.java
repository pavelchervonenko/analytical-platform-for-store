package com.storeanalytics.auth.bootstrap;

import java.util.UUID;

public record BootstrapAdminOutcome(
        Status status,
        UUID userId
) {

    public BootstrapAdminOutcome {
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        if ((status == Status.CREATED && userId == null)
                || (status == Status.USERS_EXIST && userId != null)) {
            throw new IllegalArgumentException(
                    "Bootstrap administrator outcome is inconsistent"
            );
        }
    }

    public static BootstrapAdminOutcome created(UUID userId) {
        return new BootstrapAdminOutcome(Status.CREATED, userId);
    }

    public static BootstrapAdminOutcome usersExist() {
        return new BootstrapAdminOutcome(Status.USERS_EXIST, null);
    }

    public enum Status {
        CREATED,
        USERS_EXIST
    }
}
