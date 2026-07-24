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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public JsonAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException exception
    ) throws IOException {
        HttpStatus status = HttpStatus.FORBIDDEN;
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(
                CorrelationId.HEADER_NAME,
                CorrelationId.getOrCreate(request)
        );
        objectMapper.writeValue(response.getOutputStream(), ApiErrorFactory.create(
                status,
                ApiErrorCode.ACCESS_DENIED,
                "Access is denied",
                request
        ));
    }
}
