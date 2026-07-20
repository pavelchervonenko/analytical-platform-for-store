package com.storeanalytics.auth.web;

import com.storeanalytics.auth.exception.ManagedUserNotFoundException;
import com.storeanalytics.auth.exception.UserAdministrationConflictException;
import com.storeanalytics.auth.exception.UserEmailConflictException;
import com.storeanalytics.common.web.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class UserAdministrationExceptionHandler {

    private final Clock clock;

    public UserAdministrationExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(ManagedUserNotFoundException.class)
    ResponseEntity<ApiError> handleUserNotFound(
            ManagedUserNotFoundException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(UserEmailConflictException.class)
    ResponseEntity<ApiError> handleEmailConflict(
            UserEmailConflictException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.CONFLICT, "USER_EMAIL_CONFLICT", exception.getMessage(), request);
    }

    @ExceptionHandler(UserAdministrationConflictException.class)
    ResponseEntity<ApiError> handleAdministrationConflict(
            UserAdministrationConflictException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.CONFLICT, "USER_ADMINISTRATION_CONFLICT", exception.getMessage(), request);
    }

    private ResponseEntity<ApiError> error(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(status).body(new ApiError(
                Instant.now(clock),
                status.value(),
                code,
                message,
                request.getRequestURI()
        ));
    }
}
