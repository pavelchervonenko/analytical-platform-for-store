package com.storeanalytics.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
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
        String correlationId = acceptedOrGenerated(request.getHeader(CorrelationId.HEADER_NAME));
        request.setAttribute(CorrelationId.ATTRIBUTE_NAME, correlationId);
        response.setHeader(CorrelationId.HEADER_NAME, correlationId);
        try (MDC.MDCCloseable ignored = MDC.putCloseable(
                CorrelationId.MDC_KEY, correlationId
        )) {
            filterChain.doFilter(request, response);
        }
    }

    private String acceptedOrGenerated(String candidate) {
        if (candidate != null && SAFE_VALUE.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString();
    }
}
