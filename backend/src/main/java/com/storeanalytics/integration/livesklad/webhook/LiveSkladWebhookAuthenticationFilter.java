package com.storeanalytics.integration.livesklad.webhook;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

final class LiveSkladWebhookAuthenticationFilter extends OncePerRequestFilter {

    static final String SECRET_HEADER = "X-Store-Analytics-Webhook-Token";
    static final String SALE_RETURN_PATH =
            "/api/integrations/livesklad/webhooks/sale-returns";
    static final String ORDER_RETURN_PATH =
            "/api/integrations/livesklad/webhooks/order-returns";

    private final LiveSkladWebhookProperties properties;

    LiveSkladWebhookAuthenticationFilter(LiveSkladWebhookProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!properties.enabled()) {
            response.sendError(HttpStatus.NOT_FOUND.value());
            return;
        }
        LiveSkladWebhookKind kind = kind(request.getRequestURI());
        if (kind == null) {
            response.sendError(HttpStatus.NOT_FOUND.value());
            return;
        }
        if (!HttpMethod.POST.matches(request.getMethod())) {
            response.sendError(HttpStatus.METHOD_NOT_ALLOWED.value());
            return;
        }
        if (!secretMatches(kind, request.getHeader(SECRET_HEADER))) {
            response.sendError(HttpStatus.UNAUTHORIZED.value());
            return;
        }
        if (!isJson(request.getHeader(HttpHeaders.CONTENT_TYPE))) {
            response.sendError(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value());
            return;
        }
        if (request.getContentLengthLong() > properties.maxBodyBytes()) {
            response.sendError(HttpStatus.PAYLOAD_TOO_LARGE.value());
            return;
        }
        try {
            filterChain.doFilter(
                    new BoundedLiveSkladWebhookRequest(
                            request,
                            properties.maxBodyBytes()
                    ),
                    response
            );
        } catch (LiveSkladWebhookBodyTooLargeException exception) {
            response.sendError(HttpStatus.PAYLOAD_TOO_LARGE.value());
        }
    }

    private LiveSkladWebhookKind kind(String requestUri) {
        if (SALE_RETURN_PATH.equals(requestUri)) {
            return LiveSkladWebhookKind.SALE_RETURN;
        }
        if (ORDER_RETURN_PATH.equals(requestUri)) {
            return LiveSkladWebhookKind.ORDER_RETURN;
        }
        return null;
    }

    private boolean secretMatches(
            LiveSkladWebhookKind kind,
            String supplied
    ) {
        if (supplied == null) {
            return false;
        }
        byte[] suppliedBytes = supplied.getBytes(StandardCharsets.UTF_8);
        boolean currentMatches = configuredSecretMatches(
                properties.currentSecret(kind),
                suppliedBytes
        );
        boolean previousMatches = configuredSecretMatches(
                properties.previousSecret(kind),
                suppliedBytes
        );
        return currentMatches | previousMatches;
    }

    private boolean configuredSecretMatches(String configured, byte[] supplied) {
        if (configured == null || configured.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                configured.getBytes(StandardCharsets.UTF_8),
                supplied
        );
    }

    private boolean isJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        try {
            return MediaType.APPLICATION_JSON.isCompatibleWith(
                    MediaType.parseMediaType(contentType)
            );
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
