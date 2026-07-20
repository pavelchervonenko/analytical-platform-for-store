package com.storeanalytics.auth.exception;

public class UserEmailConflictException extends RuntimeException {

    public UserEmailConflictException(String email) {
        super("Application user already exists for email: " + email);
    }
}
