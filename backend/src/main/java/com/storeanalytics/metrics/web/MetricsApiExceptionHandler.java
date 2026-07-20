package com.storeanalytics.metrics.web;

import com.storeanalytics.common.web.ApiError;
import com.storeanalytics.metrics.exception.StoreNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = {StoreKpiController.class, EmployeeKpiController.class})
public class MetricsApiExceptionHandler {

    @ExceptionHandler(StoreNotFoundException.class)
    ResponseEntity<ApiError> handleStoreNotFound(
            StoreNotFoundException exception,
            HttpServletRequest request
    ) {
        ApiError error = new ApiError(
                Instant.now(),
                HttpStatus.NOT_FOUND.value(),
                "STORE_NOT_FOUND",
                "Store was not found: " + exception.getStoreId(),
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> handleArgumentTypeMismatch(
            MethodArgumentTypeMismatchException exception,
            HttpServletRequest request
    ) {
        ApiError error = new ApiError(
                Instant.now(),
                HttpStatus.BAD_REQUEST.value(),
                "INVALID_ARGUMENT",
                "Request parameters are invalid",
                request.getRequestURI()
        );
        return ResponseEntity.badRequest().body(error);
    }
}
