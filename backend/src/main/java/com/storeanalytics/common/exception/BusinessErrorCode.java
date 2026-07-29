package com.storeanalytics.common.exception;

public enum BusinessErrorCode {

    INVALID_ARGUMENT(
            "INVALID_ARGUMENT", BusinessErrorType.INVALID_REQUEST,
            "Request parameters are invalid"
    ),
    STORE_NOT_FOUND(
            "STORE_NOT_FOUND", BusinessErrorType.NOT_FOUND,
            "Store was not found"
    ),
    EMPLOYEE_ASSIGNMENT_NOT_FOUND(
            "EMPLOYEE_ASSIGNMENT_NOT_FOUND", BusinessErrorType.NOT_FOUND,
            "Employee assignment was not found"
    ),
    PERFORMANCE_PLAN_NOT_FOUND(
            "PERFORMANCE_PLAN_NOT_FOUND", BusinessErrorType.NOT_FOUND,
            "Performance plan was not found"
    ),
    RATING_PERIOD_NOT_CLOSED(
            "RATING_PERIOD_NOT_CLOSED", BusinessErrorType.CONFLICT,
            "Rating period is not closed"
    ),
    RATING_SCHEME_CONFLICT(
            "RATING_SCHEME_CONFLICT", BusinessErrorType.CONFLICT,
            "Rating scheme conflicts with an existing version"
    ),
    RATING_SCHEME_NOT_FOUND(
            "RATING_SCHEME_NOT_FOUND", BusinessErrorType.NOT_FOUND,
            "Rating scheme was not found"
    ),
    EMPLOYEE_RATING_CONFLICT(
            "EMPLOYEE_RATING_CONFLICT", BusinessErrorType.CONFLICT,
            "Employee rating settings were changed; reload and retry"
    ),
    PAYROLL_NOT_FOUND(
            "PAYROLL_NOT_FOUND", BusinessErrorType.NOT_FOUND,
            "Payroll was not found"
    ),
    PAYROLL_SOURCE_DATA_CHANGED(
            "PAYROLL_SOURCE_DATA_CHANGED", BusinessErrorType.CONFLICT,
            "Payroll source data changed; recalculate and retry"
    ),
    PAYROLL_SCHEME_CONFLICT(
            "PAYROLL_SCHEME_CONFLICT", BusinessErrorType.CONFLICT,
            "Payroll scheme conflicts with an existing version"
    ),
    PAYROLL_STATE_CONFLICT(
            "PAYROLL_STATE_CONFLICT", BusinessErrorType.CONFLICT,
            "Payroll state does not allow this operation"
    ),
    PAYROLL_ADJUSTMENT_NOT_FOUND(
            "PAYROLL_ADJUSTMENT_NOT_FOUND", BusinessErrorType.NOT_FOUND,
            "Payroll adjustment was not found"
    ),
    USER_NOT_FOUND(
            "USER_NOT_FOUND", BusinessErrorType.NOT_FOUND,
            "Application user was not found"
    ),
    USER_EMAIL_CONFLICT(
            "USER_EMAIL_CONFLICT", BusinessErrorType.CONFLICT,
            "An application user with this email already exists"
    ),
    USER_ADMINISTRATION_CONFLICT(
            "USER_ADMINISTRATION_CONFLICT", BusinessErrorType.CONFLICT,
            "User administration operation conflicts with current state"
    ),
    INVALID_CURRENT_PASSWORD(
            "INVALID_CURRENT_PASSWORD", BusinessErrorType.INVALID_REQUEST,
            "Current password is invalid"
    ),
    PASSWORD_POLICY_VIOLATION(
            "PASSWORD_POLICY_VIOLATION", BusinessErrorType.INVALID_REQUEST,
            "Password does not satisfy the password policy"
    ),
    LOGIN_THROTTLED(
            "LOGIN_THROTTLED", BusinessErrorType.RATE_LIMITED,
            "Too many login attempts; try again later"
    ),
    CURRENT_SESSION_REQUIRES_LOGOUT(
            "CURRENT_SESSION_REQUIRES_LOGOUT", BusinessErrorType.CONFLICT,
            "Use logout to end the current browser session"
    ),
    ACTIVE_SYNC_JOB_EXISTS(
            "ACTIVE_SYNC_JOB_EXISTS", BusinessErrorType.CONFLICT,
            "An active synchronization job already exists"
    ),
    ACTIVE_REPORT_BACKFILL_JOB_EXISTS(
            "ACTIVE_REPORT_BACKFILL_JOB_EXISTS", BusinessErrorType.CONFLICT,
            "An active report backfill job already exists"
    ),
    REPORT_BACKFILL_IDEMPOTENCY_CONFLICT(
            "REPORT_BACKFILL_IDEMPOTENCY_CONFLICT", BusinessErrorType.CONFLICT,
            "Idempotency key belongs to another report backfill request"
    ),
    IDEMPOTENCY_KEY_CONFLICT(
            "IDEMPOTENCY_KEY_CONFLICT", BusinessErrorType.CONFLICT,
            "Idempotency key was already used for another request"
    ),
    REPORT_BACKFILL_JOB_NOT_FOUND(
            "REPORT_BACKFILL_JOB_NOT_FOUND", BusinessErrorType.NOT_FOUND,
            "Report backfill job was not found"
    ),
    SYNC_JOB_NOT_FOUND(
            "SYNC_JOB_NOT_FOUND", BusinessErrorType.NOT_FOUND,
            "Synchronization job was not found"
    ),
    STORE_SYNC_FAILED(
            "STORE_SYNC_FAILED", BusinessErrorType.UPSTREAM_FAILURE,
            "Store synchronization failed"
    ),
    REPORT_NOT_FOUND(
            "REPORT_NOT_FOUND", BusinessErrorType.NOT_FOUND,
            "Report was not found"
    ),
    EMPLOYEE_SYNC_FAILED(
            "EMPLOYEE_SYNC_FAILED", BusinessErrorType.UPSTREAM_FAILURE,
            "Employee synchronization failed"
    ),
    SALES_SYNC_WINDOW_TOO_LARGE(
            "SALES_SYNC_WINDOW_TOO_LARGE", BusinessErrorType.UNPROCESSABLE,
            "Sales synchronization window is too large"
    ),
    SALES_SYNC_FAILED(
            "SALES_SYNC_FAILED", BusinessErrorType.UPSTREAM_FAILURE,
            "Sales synchronization failed"
    ),
    RETURN_SYNC_WINDOW_TOO_LARGE(
            "RETURN_SYNC_WINDOW_TOO_LARGE", BusinessErrorType.UNPROCESSABLE,
            "Return synchronization window is too large"
    ),
    RETURN_SYNC_FAILED(
            "RETURN_SYNC_FAILED", BusinessErrorType.UPSTREAM_FAILURE,
            "Return synchronization failed"
    ),
    PRODUCT_NOT_FOUND(
            "PRODUCT_NOT_FOUND", BusinessErrorType.NOT_FOUND,
            "Product was not found"
    ),
    PRODUCT_CLASSIFICATION_CONFLICT(
            "PRODUCT_CLASSIFICATION_CONFLICT", BusinessErrorType.CONFLICT,
            "Product classification conflicts with current state"
    );

    private final String value;
    private final BusinessErrorType type;
    private final String clientMessage;

    BusinessErrorCode(String value, BusinessErrorType type, String clientMessage) {
        this.value = value;
        this.type = type;
        this.clientMessage = clientMessage;
    }

    public String value() {
        return value;
    }

    public BusinessErrorType type() {
        return type;
    }

    public String clientMessage() {
        return clientMessage;
    }
}
