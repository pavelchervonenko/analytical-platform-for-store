package com.storeanalytics.common.web;

import com.storeanalytics.sync.exception.EmployeeSyncException;
import com.storeanalytics.sync.exception.ReturnSyncCapacityException;
import com.storeanalytics.sync.exception.ReturnSyncException;
import com.storeanalytics.sync.exception.SalesSyncCapacityException;
import com.storeanalytics.sync.exception.SalesSyncException;
import com.storeanalytics.sync.exception.StoreSyncException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        ApiError error = new ApiError(
                Instant.now(),
                HttpStatus.BAD_REQUEST.value(),
                "VALIDATION_ERROR",
                "Request validation failed",
                request.getRequestURI()
        );
        return ResponseEntity.badRequest().body(error);
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ApiError> handleIllegalState(IllegalStateException exception, HttpServletRequest request) {
        ApiError error = new ApiError(
                Instant.now(),
                HttpStatus.CONFLICT.value(),
                "ILLEGAL_STATE",
                exception.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(StoreSyncException.class)
    ResponseEntity<ApiError> handleStoreSync(StoreSyncException exception, HttpServletRequest request) {
        ApiError error = new ApiError(
                Instant.now(),
                HttpStatus.BAD_GATEWAY.value(),
                "STORE_SYNC_FAILED",
                "Store synchronization failed; sync run: " + exception.getSyncRunId(),
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(error);
    }

    @ExceptionHandler(EmployeeSyncException.class)
    ResponseEntity<ApiError> handleEmployeeSync(
            EmployeeSyncException exception,
            HttpServletRequest request
    ) {
        ApiError error = new ApiError(
                Instant.now(),
                HttpStatus.BAD_GATEWAY.value(),
                "EMPLOYEE_SYNC_FAILED",
                "Employee synchronization failed; sync run: " + exception.getSyncRunId(),
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(error);
    }

    @ExceptionHandler(SalesSyncCapacityException.class)
    ResponseEntity<ApiError> handleSalesSyncCapacity(
            SalesSyncCapacityException exception,
            HttpServletRequest request
    ) {
        ApiError error = new ApiError(
                Instant.now(),
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                "SALES_SYNC_WINDOW_TOO_LARGE",
                "Sales window contains " + exception.getRecordCount()
                        + " records; maximum is " + exception.getMaximumRecordCount()
                        + "; sync run: " + exception.getSyncRunId(),
                request.getRequestURI()
        );
        return ResponseEntity.unprocessableEntity().body(error);
    }

    @ExceptionHandler(SalesSyncException.class)
    ResponseEntity<ApiError> handleSalesSync(
            SalesSyncException exception,
            HttpServletRequest request
    ) {
        ApiError error = new ApiError(
                Instant.now(),
                HttpStatus.BAD_GATEWAY.value(),
                "SALES_SYNC_FAILED",
                "Sales synchronization failed; sync run: " + exception.getSyncRunId(),
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(error);
    }

    @ExceptionHandler(ReturnSyncCapacityException.class)
    ResponseEntity<ApiError> handleReturnSyncCapacity(
            ReturnSyncCapacityException exception,
            HttpServletRequest request
    ) {
        ApiError error = new ApiError(
                Instant.now(),
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                "RETURN_SYNC_WINDOW_TOO_LARGE",
                "Return window contains " + exception.getRecordCount()
                        + " active documents; maximum is "
                        + exception.getMaximumRecordCount()
                        + "; sync run: " + exception.getSyncRunId(),
                request.getRequestURI()
        );
        return ResponseEntity.unprocessableEntity().body(error);
    }

    @ExceptionHandler(ReturnSyncException.class)
    ResponseEntity<ApiError> handleReturnSync(
            ReturnSyncException exception,
            HttpServletRequest request
    ) {
        ApiError error = new ApiError(
                Instant.now(),
                HttpStatus.BAD_GATEWAY.value(),
                "RETURN_SYNC_FAILED",
                "Return synchronization failed; sync run: "
                        + exception.getSyncRunId(),
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(error);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadableRequest(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        ApiError error = new ApiError(
                Instant.now(),
                HttpStatus.BAD_REQUEST.value(),
                "MALFORMED_REQUEST",
                "Request body is missing or malformed",
                request.getRequestURI()
        );
        return ResponseEntity.badRequest().body(error);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> handleIllegalArgument(
            IllegalArgumentException exception,
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
