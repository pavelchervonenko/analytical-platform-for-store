package com.storeanalytics.auth.exception;

import java.util.UUID;

public class ManagedUserNotFoundException extends RuntimeException {

    public ManagedUserNotFoundException(UUID userId) {
        super("Application user not found: " + userId);
    }
}
