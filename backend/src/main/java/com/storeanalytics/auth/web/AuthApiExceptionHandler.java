package com.storeanalytics.auth.web;

import com.storeanalytics.auth.exception.InvalidCurrentPasswordException;
import com.storeanalytics.auth.exception.PasswordPolicyViolationException;
import com.storeanalytics.common.web.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AuthApiExceptionHandler {

    private final Clock clock;

    public AuthApiExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiError> handleAuthenticationFailure(
            AuthenticationException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.UNAUTHORIZED,
                "INVALID_CREDENTIALS",
                "Email or password is invalid",
                request
        );
    }

    @ExceptionHandler(InvalidCurrentPasswordException.class)
    ResponseEntity<ApiError> handleInvalidCurrentPassword(
            InvalidCurrentPasswordException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.BAD_REQUEST,
                "INVALID_CURRENT_PASSWORD",
                exception.getMessage(),
                request
        );
    }

    @ExceptionHandler(PasswordPolicyViolationException.class)
    ResponseEntity<ApiError> handlePasswordPolicyViolation(
            PasswordPolicyViolationException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.BAD_REQUEST,
                "PASSWORD_POLICY_VIOLATION",
                exception.getMessage(),
                request
        );
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
