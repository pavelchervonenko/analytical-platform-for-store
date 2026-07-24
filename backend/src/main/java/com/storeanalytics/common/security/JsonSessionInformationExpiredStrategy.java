package com.storeanalytics.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    public JsonSessionInformationExpiredStrategy(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void onExpiredSessionDetected(SessionInformationExpiredEvent event)
            throws IOException {
        HttpServletRequest request = event.getRequest();
        HttpServletResponse response = event.getResponse();
        HttpStatus status = HttpStatus.UNAUTHORIZED;
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(
                CorrelationId.HEADER_NAME,
                CorrelationId.getOrCreate(request)
        );
        objectMapper.writeValue(response.getOutputStream(), ApiErrorFactory.create(
                status,
                ApiErrorCode.SESSION_EXPIRED,
                "The session has expired",
                request
        ));
    }
}
