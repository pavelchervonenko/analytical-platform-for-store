package com.storeanalytics.common.web;

import com.storeanalytics.common.exception.BusinessErrorType;
import com.storeanalytics.common.exception.BusinessException;
import com.storeanalytics.common.exception.PreconditionFailedException;
import com.storeanalytics.common.exception.PreconditionRequiredException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiError> handleBusiness(
            BusinessException exception,
            HttpServletRequest request
    ) {
        HttpStatus status = status(exception.getErrorCode().type());
        return ResponseEntity.status(status).body(
                ApiErrorFactory.create(status, exception, request)
        );
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        BindException.class,
        ConstraintViolationException.class
    })
    ResponseEntity<ApiError> handleValidation(
            Exception exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.VALIDATION_ERROR,
                "Request validation failed",
                request
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadableRequest(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        if (hasPayloadTooLargeCause(exception)) {
            return payloadTooLarge(request);
        }
        return error(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.MALFORMED_REQUEST,
                "Request body is missing or malformed",
                request
        );
    }

    @ExceptionHandler(RequestBodyTooLargeException.class)
    ResponseEntity<ApiError> handlePayloadTooLarge(
            RequestBodyTooLargeException exception,
            HttpServletRequest request
    ) {
        return payloadTooLarge(request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> handleArgumentTypeMismatch(
            MethodArgumentTypeMismatchException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.INVALID_ARGUMENT,
                "Request parameters are invalid",
                request
        );
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiError> handleMissingParameter(
            MissingServletRequestParameterException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.MISSING_PARAMETER,
                "A required request parameter is missing",
                request
        );
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiError> handleMissingHeader(
            MissingRequestHeaderException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.MISSING_PARAMETER,
                "A required request header is missing",
                request
        );
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> handleAccessDenied(
            AccessDeniedException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.FORBIDDEN,
                ApiErrorCode.ACCESS_DENIED,
                "Access is denied",
                request
        );
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ApiError> handleConcurrentModification(
            OptimisticLockingFailureException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.CONFLICT,
                ApiErrorCode.CONCURRENT_MODIFICATION,
                "The resource was changed; reload and retry",
                request
        );
    }

    @ExceptionHandler(PreconditionRequiredException.class)
    ResponseEntity<ApiError> handlePreconditionRequired(
            PreconditionRequiredException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.PRECONDITION_REQUIRED,
                ApiErrorCode.PRECONDITION_REQUIRED,
                "A current resource precondition is required",
                request
        );
    }

    @ExceptionHandler(PreconditionFailedException.class)
    ResponseEntity<ApiError> handlePreconditionFailed(
            PreconditionFailedException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.PRECONDITION_FAILED,
                ApiErrorCode.PRECONDITION_FAILED,
                "The resource was changed; reload and retry",
                request
        );
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.METHOD_NOT_ALLOWED,
                ApiErrorCode.METHOD_NOT_ALLOWED,
                "HTTP method is not supported for this resource",
                request
        );
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                ApiErrorCode.UNSUPPORTED_MEDIA_TYPE,
                "Request media type is not supported",
                request
        );
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> handleResourceNotFound(
            NoResourceFoundException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.NOT_FOUND,
                ApiErrorCode.RESOURCE_NOT_FOUND,
                "Resource was not found",
                request
        );
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(
            Exception exception,
            HttpServletRequest request
    ) {
        String requestId = CorrelationId.getOrCreateRequestId(request);
        LOGGER.error(
                "Unhandled request failure requestId={} method={} path={}",
                requestId,
                request.getMethod(),
                request.getRequestURI(),
                exception
        );
        return error(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ApiErrorCode.INTERNAL_ERROR,
                "An unexpected error occurred",
                request
        );
    }

    private ResponseEntity<ApiError> error(
            HttpStatus status,
            ApiErrorCode code,
            String message,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(status).body(
                ApiErrorFactory.create(status, code, message, request)
        );
    }

    private ResponseEntity<ApiError> payloadTooLarge(HttpServletRequest request) {
        HttpStatus status = HttpStatus.CONTENT_TOO_LARGE;
        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(ApiErrorFactory.create(
                        status,
                        ApiErrorCode.PAYLOAD_TOO_LARGE,
                        "Request body is too large",
                        request
                ));
    }

    private boolean hasPayloadTooLargeCause(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof RequestBodyTooLargeException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private HttpStatus status(BusinessErrorType type) {
        return switch (type) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case UNPROCESSABLE -> HttpStatus.UNPROCESSABLE_CONTENT;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case UPSTREAM_FAILURE -> HttpStatus.BAD_GATEWAY;
        };
    }
}
