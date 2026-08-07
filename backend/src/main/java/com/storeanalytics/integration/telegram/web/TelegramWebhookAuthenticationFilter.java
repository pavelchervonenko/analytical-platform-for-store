package com.storeanalytics.integration.telegram.web;

import com.storeanalytics.notification.config.TelegramNotificationProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

public class TelegramWebhookAuthenticationFilter extends OncePerRequestFilter {

    public static final String SECRET_HEADER =
            "X-Telegram-Bot-Api-Secret-Token";
    private final TelegramNotificationProperties properties;

    public TelegramWebhookAuthenticationFilter(
            TelegramNotificationProperties properties
    ) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!properties.enabled() || !properties.webhookEnabled()) {
            response.sendError(HttpStatus.NOT_FOUND.value());
            return;
        }
        if (!properties.botCode().equals(botCode(request.getRequestURI()))) {
            response.sendError(HttpStatus.NOT_FOUND.value());
            return;
        }
        if (!secretMatches(request.getHeader(SECRET_HEADER))) {
            response.sendError(HttpStatus.UNAUTHORIZED.value());
            return;
        }
        String contentType = request.getHeader(HttpHeaders.CONTENT_TYPE);
        if (!isJson(contentType)) {
            response.sendError(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value());
            return;
        }
        long contentLength = request.getContentLengthLong();
        if (contentLength > properties.webhookMaxBodyBytes()) {
            response.sendError(HttpStatus.PAYLOAD_TOO_LARGE.value());
            return;
        }
        try {
            filterChain.doFilter(
                    new BoundedTelegramWebhookRequest(
                            request,
                            properties.webhookMaxBodyBytes()
                    ),
                    response
            );
        } catch (TelegramWebhookBodyTooLargeException exception) {
            response.sendError(HttpStatus.PAYLOAD_TOO_LARGE.value());
        }
    }

    private String botCode(String requestUri) {
        String prefix = "/api/integrations/telegram/";
        String suffix = "/webhook";
        if (requestUri == null || !requestUri.startsWith(prefix)
                || !requestUri.endsWith(suffix)) {
            return "";
        }
        return requestUri.substring(prefix.length(), requestUri.length() - suffix.length());
    }

    private boolean secretMatches(String supplied) {
        if (supplied == null) {
            return false;
        }
        byte[] suppliedBytes = supplied.getBytes(StandardCharsets.UTF_8);
        boolean currentMatches = configuredSecretMatches(
                properties.webhookSecret(),
                suppliedBytes
        );
        boolean previousMatches = configuredSecretMatches(
                properties.webhookPreviousSecret(),
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
