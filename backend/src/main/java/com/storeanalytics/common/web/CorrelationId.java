package com.storeanalytics.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

public final class CorrelationId {

    public static final String HEADER_NAME = "X-Correlation-ID";
    public static final String ATTRIBUTE_NAME = CorrelationId.class.getName();
    public static final String MDC_KEY = "correlationId";

    private CorrelationId() {
    }

    public static String getOrCreate(HttpServletRequest request) {
        Object existing = request.getAttribute(ATTRIBUTE_NAME);
        if (existing instanceof String value && !value.isBlank()) {
            return value;
        }
        String generated = UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE_NAME, generated);
        return generated;
    }
}
