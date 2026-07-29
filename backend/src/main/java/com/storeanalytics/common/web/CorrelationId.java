package com.storeanalytics.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

public final class CorrelationId {

    public static final String HEADER_NAME = "X-Correlation-ID";
    public static final String REQUEST_ID_ATTRIBUTE_NAME =
            CorrelationId.class.getName() + ".requestId";
    public static final String CLIENT_HINT_ATTRIBUTE_NAME =
            CorrelationId.class.getName() + ".clientHint";
    public static final String REQUEST_ID_MDC_KEY = "request.id";
    public static final String CLIENT_HINT_MDC_KEY = "client.correlation_id";

    private CorrelationId() {
    }

    public static String getOrCreateRequestId(HttpServletRequest request) {
        Object existing = request.getAttribute(REQUEST_ID_ATTRIBUTE_NAME);
        if (existing instanceof String value && !value.isBlank()) {
            return value;
        }
        String generated = UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ID_ATTRIBUTE_NAME, generated);
        return generated;
    }

    public static String getClientHint(HttpServletRequest request) {
        Object value = request.getAttribute(CLIENT_HINT_ATTRIBUTE_NAME);
        return value instanceof String clientHint ? clientHint : null;
    }
}
