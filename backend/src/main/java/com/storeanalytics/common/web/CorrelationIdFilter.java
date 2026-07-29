package com.storeanalytics.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Enumeration;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final int MAX_LENGTH = 64;
    private static final Pattern SAFE_VALUE = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._-]{0," + (MAX_LENGTH - 1) + "}"
    );

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = CorrelationId.getOrCreateRequestId(request);
        String clientHint = validatedClientHint(request);
        if (clientHint != null) {
            request.setAttribute(CorrelationId.CLIENT_HINT_ATTRIBUTE_NAME, clientHint);
        }
        response.setHeader(CorrelationId.HEADER_NAME, requestId);
        try (MDC.MDCCloseable ignoredRequestId = MDC.putCloseable(
                CorrelationId.REQUEST_ID_MDC_KEY, requestId
        )) {
            doFilterWithClientHint(request, response, filterChain, clientHint);
        }
    }

    private void doFilterWithClientHint(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain,
            String clientHint
    ) throws ServletException, IOException {
        if (clientHint == null) {
            filterChain.doFilter(request, response);
            return;
        }
        try (MDC.MDCCloseable ignoredClientHint = MDC.putCloseable(
                CorrelationId.CLIENT_HINT_MDC_KEY, clientHint
        )) {
            filterChain.doFilter(request, response);
        }
    }

    private String validatedClientHint(HttpServletRequest request) {
        Enumeration<String> values = request.getHeaders(CorrelationId.HEADER_NAME);
        if (values == null || !values.hasMoreElements()) {
            return null;
        }
        String candidate = values.nextElement();
        if (values.hasMoreElements() || !SAFE_VALUE.matcher(candidate).matches()) {
            return null;
        }
        return candidate;
    }
}
