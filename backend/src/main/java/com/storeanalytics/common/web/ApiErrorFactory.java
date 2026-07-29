package com.storeanalytics.common.web;

import com.storeanalytics.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.http.HttpStatus;

public final class ApiErrorFactory {

    private ApiErrorFactory() {
    }

    public static ApiError create(
            HttpStatus status,
            ApiErrorCode code,
            String message,
            HttpServletRequest request
    ) {
        return create(status, code.name(), message, request);
    }

    public static ApiError create(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request
    ) {
        return new ApiError(
                Instant.now(),
                status.value(),
                code,
                message,
                request.getRequestURI(),
                CorrelationId.getOrCreateRequestId(request)
        );
    }

    public static ApiError create(
            HttpStatus status,
            BusinessException exception,
            HttpServletRequest request
    ) {
        return create(
                status,
                exception.getErrorCode().value(),
                exception.getErrorCode().clientMessage(),
                request
        );
    }
}
