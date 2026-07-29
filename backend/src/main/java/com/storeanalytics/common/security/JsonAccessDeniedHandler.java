package com.storeanalytics.common.security;

import tools.jackson.databind.ObjectMapper;
import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.common.web.ApiErrorCode;
import com.storeanalytics.common.web.ApiErrorFactory;
import com.storeanalytics.common.web.CorrelationId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;

@Component
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;
    private final ClientAddressResolver clientAddressResolver;
    private final SecurityAuditLogger securityAuditLogger;

    public JsonAccessDeniedHandler(
            ObjectMapper objectMapper,
            ClientAddressResolver clientAddressResolver,
            SecurityAuditLogger securityAuditLogger
    ) {
        this.objectMapper = objectMapper;
        this.clientAddressResolver = clientAddressResolver;
        this.securityAuditLogger = securityAuditLogger;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException exception
    ) throws IOException {
        UUID userId = authenticatedUserId();
        ClientAddress clientAddress = clientAddressResolver.resolve(request);
        if (exception instanceof CsrfException) {
            securityAuditLogger.csrfRejected(userId, clientAddress);
        } else {
            securityAuditLogger.accessDenied(userId, clientAddress);
        }

        HttpStatus status = HttpStatus.FORBIDDEN;
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(
                CorrelationId.HEADER_NAME,
                CorrelationId.getOrCreateRequestId(request)
        );
        objectMapper.writeValue(response.getOutputStream(), ApiErrorFactory.create(
                status,
                ApiErrorCode.ACCESS_DENIED,
                "Access is denied",
                request
        ));
    }

    private UUID authenticatedUserId() {
        Authentication authentication = SecurityContextHolder.getContext()
                .getAuthentication();
        return authentication != null
                && authentication.getPrincipal()
                instanceof AppUserPrincipal principal
                ? principal.getUserId()
                : null;
    }
}
