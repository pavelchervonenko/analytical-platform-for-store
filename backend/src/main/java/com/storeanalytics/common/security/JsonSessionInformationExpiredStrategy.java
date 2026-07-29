package com.storeanalytics.common.security;

import tools.jackson.databind.ObjectMapper;
import com.storeanalytics.common.web.ApiErrorCode;
import com.storeanalytics.common.web.ApiErrorFactory;
import com.storeanalytics.common.web.CorrelationId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.session.SessionInformationExpiredEvent;
import org.springframework.security.web.session.SessionInformationExpiredStrategy;
import org.springframework.stereotype.Component;

@Component
public class JsonSessionInformationExpiredStrategy
        implements SessionInformationExpiredStrategy {

    private final ObjectMapper objectMapper;
    private final ClientAddressResolver clientAddressResolver;
    private final SecurityAuditLogger securityAuditLogger;

    public JsonSessionInformationExpiredStrategy(
            ObjectMapper objectMapper,
            ClientAddressResolver clientAddressResolver,
            SecurityAuditLogger securityAuditLogger
    ) {
        this.objectMapper = objectMapper;
        this.clientAddressResolver = clientAddressResolver;
        this.securityAuditLogger = securityAuditLogger;
    }

    @Override
    public void onExpiredSessionDetected(SessionInformationExpiredEvent event)
            throws IOException {
        HttpServletRequest request = event.getRequest();
        HttpServletResponse response = event.getResponse();
        securityAuditLogger.sessionExpired(clientAddressResolver.resolve(request));
        HttpStatus status = HttpStatus.UNAUTHORIZED;
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(
                CorrelationId.HEADER_NAME,
                CorrelationId.getOrCreateRequestId(request)
        );
        objectMapper.writeValue(response.getOutputStream(), ApiErrorFactory.create(
                status,
                ApiErrorCode.SESSION_EXPIRED,
                "The session has expired",
                request
        ));
    }
}
