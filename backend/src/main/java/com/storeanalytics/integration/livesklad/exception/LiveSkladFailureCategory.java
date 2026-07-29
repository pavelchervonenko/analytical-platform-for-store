package com.storeanalytics.integration.livesklad.exception;

public enum LiveSkladFailureCategory {
    AUTHENTICATION,
    AUTHORIZATION,
    NOT_FOUND,
    CONFLICT,
    RATE_LIMIT,
    CLIENT_REQUEST,
    UPSTREAM_SERVER,
    TRANSPORT,
    PAYLOAD
}
