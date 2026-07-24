package com.storeanalytics.common.web;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storeanalytics.performance.exception.RatingSchemeConflictException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class ApiExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FailureController())
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new CorrelationIdFilter())
                .build();
    }

    @Test
    void returnsTypedBusinessErrorWithoutInternalMessage() throws Exception {
        mockMvc.perform(get("/test/business")
                        .header(CorrelationId.HEADER_NAME, "client-trace_123"))
                .andExpect(status().isConflict())
                .andExpect(header().string(
                        CorrelationId.HEADER_NAME, "client-trace_123"
                ))
                .andExpect(jsonPath("$.code").value("RATING_SCHEME_CONFLICT"))
                .andExpect(jsonPath("$.message").value(
                        "Rating scheme conflicts with an existing version"
                ))
                .andExpect(jsonPath("$.correlationId").value("client-trace_123"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void unexpectedIllegalStateIsNeutralInternalError() throws Exception {
        mockMvc.perform(get("/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value(
                        "An unexpected error occurred"
                ))
                .andExpect(jsonPath("$.correlationId").value(
                        matchesPattern("[0-9a-f-]{36}")
                ))
                .andExpect(header().string(
                        CorrelationId.HEADER_NAME,
                        matchesPattern("[0-9a-f-]{36}")
                ));
    }

    @Test
    void invalidIncomingCorrelationIdIsNotReflected() throws Exception {
        mockMvc.perform(get("/test/ok")
                        .header(CorrelationId.HEADER_NAME, "unsafe value"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        CorrelationId.HEADER_NAME,
                        matchesPattern("[0-9a-f-]{36}")
                ));
    }

    @RestController
    static class FailureController {

        @GetMapping("/test/business")
        Map<String, Object> business() {
            throw new RatingSchemeConflictException(
                    "database constraint uk_rating_scheme_code was violated"
            );
        }

        @GetMapping("/test/unexpected")
        Map<String, Object> unexpected() {
            throw new IllegalStateException(
                    "jdbc:postgresql://internal-host/private?password=secret"
            );
        }

        @GetMapping("/test/ok")
        Map<String, Object> ok() {
            return Map.of("ok", true);
        }
    }
}
