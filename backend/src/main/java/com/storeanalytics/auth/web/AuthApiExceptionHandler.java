package com.storeanalytics.auth.web;

import com.storeanalytics.auth.exception.LoginThrottledException;
import com.storeanalytics.common.web.ApiError;
import com.storeanalytics.common.web.ApiErrorCode;
import com.storeanalytics.common.web.ApiErrorFactory;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AuthApiExceptionHandler {

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiError> handleAuthenticationFailure(
            AuthenticationException exception,
            HttpServletRequest request
    ) {
        HttpStatus status = HttpStatus.UNAUTHORIZED;
        return ResponseEntity.status(status).body(ApiErrorFactory.create(
                status,
                ApiErrorCode.INVALID_CREDENTIALS,
                "Email or password is invalid",
                request
        ));
    }

    @ExceptionHandler(LoginThrottledException.class)
    ResponseEntity<ApiError> handleLoginThrottled(
            LoginThrottledException exception,
            HttpServletRequest request
    ) {
        long retryAfterSeconds = Math.max(
                1,
                (exception.getRetryAfter().toMillis() + 999) / 1000
        );
        HttpStatus status = HttpStatus.TOO_MANY_REQUESTS;
        return ResponseEntity.status(status)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds))
                .body(ApiErrorFactory.create(status, exception, request));
    }
}
