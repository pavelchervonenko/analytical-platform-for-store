package com.storeanalytics.common.web;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storeanalytics.common.config.ResourceLimitsProperties;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

class RequestBodyLimitMvcTest {

    private static final String CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";

    @Test
    void mapsStreamingOverflowThroughJsonConverterToStablePayloadTooLarge()
            throws Exception {
        ObjectMapper objectMapper = new ObjectMapper()
                .rebuild()
                .findAndAddModules()
                .build();
        RequestBodyLimitFilter bodyLimitFilter = new RequestBodyLimitFilter(
                properties(8),
                objectMapper
        );
        Filter hideContentLength = (request, response, chain) -> chain.doFilter(
                new HttpServletRequestWrapper((HttpServletRequest) request) {
                    @Override
                    public int getContentLength() {
                        return -1;
                    }

                    @Override
                    public long getContentLengthLong() {
                        return -1;
                    }
                },
                response
        );
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new JsonController())
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(
                        new CorrelationIdFilter(),
                        hideContentLength,
                        bodyLimitFilter
                )
                .build();

        mockMvc.perform(post("/api/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":123}"))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"))
                .andExpect(jsonPath("$.message").value(
                        "Request body is too large"
                ))
                .andExpect(jsonPath("$.correlationId").value(
                        matchesPattern("[0-9a-f-]{36}")
                ))
                .andExpect(header().string(
                        CorrelationId.HEADER_NAME,
                        matchesPattern("[0-9a-f-]{36}")
                ))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(CONTENT_TYPE_OPTIONS, "nosniff"));
    }

    private ResourceLimitsProperties properties(long maximumBodyBytes) {
        ResourceLimitsProperties.Http http = new ResourceLimitsProperties.Http(
                DataSize.ofKilobytes(8),
                DataSize.ofBytes(maximumBodyBytes),
                DataSize.ofBytes(maximumBodyBytes),
                DataSize.ofBytes(maximumBodyBytes),
                Duration.ofSeconds(10),
                Duration.ofSeconds(20),
                512,
                100,
                64,
                8,
                128,
                100,
                256
        );
        ResourceLimitsProperties.Database database =
                new ResourceLimitsProperties.Database(
                        10,
                        2,
                        5_000,
                        3_000,
                        600_000,
                        1_800_000,
                        120_000,
                        1
                );
        return new ResourceLimitsProperties(http, database);
    }

    @RestController
    static class JsonController {

        @PostMapping("/api/test")
        Map<String, Object> consume(@RequestBody Map<String, Object> body) {
            return body;
        }
    }
}
