package com.storeanalytics.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.storeanalytics.common.exception.PreconditionFailedException;
import com.storeanalytics.common.exception.PreconditionRequiredException;
import com.storeanalytics.performance.exception.RatingSchemeConflictException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
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
        MvcResult result = mockMvc.perform(get("/test/business")
                                .header(
                                        CorrelationId.HEADER_NAME,
                                        "client-trace_123"
                                ))
                .andExpect(status().isConflict())
                .andExpect(header().string(
                        CorrelationId.HEADER_NAME,
                        matchesPattern("[0-9a-f-]{36}")
                ))
                .andExpect(jsonPath("$.code").value("RATING_SCHEME_CONFLICT"))
                .andExpect(jsonPath("$.message").value(
                        "Rating scheme conflicts with an existing version"
                ))
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON
                ))
                .andReturn();

        String requestId = result.getResponse()
                .getHeader(CorrelationId.HEADER_NAME);
        JsonNode body = new ObjectMapper().readTree(
                result.getResponse().getContentAsString()
        );
        assertThat(body.path("correlationId").asString()).isEqualTo(requestId);
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("client-trace_123");
    }

    @Test
    void returnsStableConditionalRequestErrors() throws Exception {
        mockMvc.perform(get("/test/precondition-required"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
        mockMvc.perform(get("/test/precondition-failed"))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"))
                .andExpect(jsonPath("$.message").value(
                        "The resource was changed; reload and retry"
                ));
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

        @GetMapping("/test/precondition-required")
        Map<String, Object> preconditionRequired() {
            throw new PreconditionRequiredException("received headers are absent");
        }

        @GetMapping("/test/precondition-failed")
        Map<String, Object> preconditionFailed() {
            throw new PreconditionFailedException("received ETag was secret-looking");
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
