package com.storeanalytics.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.storeanalytics.common.config.ResourceLimitsProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class RequestBodyLimitFilterTest {

    private static final int MAXIMUM_BYTES = 8;
    private static final String CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";

    private final ObjectMapper objectMapper = new ObjectMapper()
            .rebuild()
            .findAndAddModules()
            .build();
    private final RequestBodyLimitFilter filter = new RequestBodyLimitFilter(
            properties(MAXIMUM_BYTES),
            objectMapper
    );

    @Test
    void rejectsOversizedDeclaredBodyBeforeDownstreamProcessing() throws Exception {
        MockHttpServletRequest request = request(new byte[MAXIMUM_BYTES + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean downstreamCalled = new AtomicBoolean();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                downstreamCalled.set(true)
        );

        assertThat(downstreamCalled).isFalse();
        assertPayloadTooLarge(response);
    }

    @Test
    void acceptsBodyAtExactByteBoundary() throws Exception {
        byte[] body = new byte[MAXIMUM_BYTES];
        MockHttpServletRequest request = request(body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean downstreamCalled = new AtomicBoolean();

        filter.doFilter(request, response, (limitedRequest, ignoredResponse) -> {
            assertThat(limitedRequest.getInputStream().readAllBytes())
                    .containsExactly(body);
            downstreamCalled.set(true);
        });

        assertThat(downstreamCalled).isTrue();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void rejectsUnknownLengthBodyByActuallyConsumedBytes() throws Exception {
        MockHttpServletRequest request = requestWithReportedLength(
                new byte[MAXIMUM_BYTES + 1],
                -1
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (limitedRequest, ignoredResponse) ->
                limitedRequest.getInputStream().readAllBytes()
        );

        assertPayloadTooLarge(response);
    }

    @Test
    void rejectsBodyWhoseDeclaredLengthUnderstatesActualBytes() throws Exception {
        MockHttpServletRequest request = requestWithReportedLength(
                new byte[MAXIMUM_BYTES + 1],
                1
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (limitedRequest, ignoredResponse) ->
                limitedRequest.getInputStream().readAllBytes()
        );

        assertPayloadTooLarge(response);
    }

    @Test
    void readerLimitCountsEncodedBytesRatherThanCharacters() throws Exception {
        MockHttpServletRequest request = requestWithReportedLength(
                "абв".getBytes(StandardCharsets.UTF_8),
                -1
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestBodyLimitFilter fourByteFilter = new RequestBodyLimitFilter(
                properties(4),
                objectMapper
        );

        fourByteFilter.doFilter(request, response, (limitedRequest, ignoredResponse) ->
                limitedRequest.getReader().readLine()
        );

        assertPayloadTooLarge(response);
    }

    @Test
    void skippingCannotBypassActualByteLimit() throws Exception {
        MockHttpServletRequest request = requestWithReportedLength(
                new byte[MAXIMUM_BYTES + 1],
                -1
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (limitedRequest, ignoredResponse) ->
                limitedRequest.getInputStream().skip(Long.MAX_VALUE)
        );

        assertPayloadTooLarge(response);
    }

    @Test
    void doesNotInterfereWithNonApiRoutes() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/actuator/health"
        );
        request.setContent(new byte[MAXIMUM_BYTES + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean downstreamCalled = new AtomicBoolean();

        filter.doFilter(request, response, (rawRequest, ignoredResponse) -> {
            assertThat(rawRequest.getInputStream().readAllBytes()).hasSize(
                    MAXIMUM_BYTES + 1
            );
            downstreamCalled.set(true);
        });

        assertThat(downstreamCalled).isTrue();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    private MockHttpServletRequest request(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/test");
        request.setContent(body);
        return request;
    }

    private MockHttpServletRequest requestWithReportedLength(
            byte[] body,
            long reportedLength
    ) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/test"
        ) {
            @Override
            public long getContentLengthLong() {
                return reportedLength;
            }
        };
        request.setContent(body);
        return request;
    }

    private void assertPayloadTooLarge(MockHttpServletResponse response)
            throws Exception {
        assertThat(response.getStatus())
                .isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("no-store");
        assertThat(response.getHeader(CONTENT_TYPE_OPTIONS))
                .isEqualTo("nosniff");
        assertThat(response.getHeader(CorrelationId.HEADER_NAME))
                .matches("[0-9a-f-]{36}");

        JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
        assertThat(body.path("status").intValue())
                .isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
        assertThat(body.path("code").asString()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(body.path("message").asString())
                .isEqualTo("Request body is too large");
        assertThat(body.path("correlationId").asString())
                .isEqualTo(response.getHeader(CorrelationId.HEADER_NAME));
    }

    private ResourceLimitsProperties properties(long maximumBodyBytes) {
        if (maximumBodyBytes < 1) {
            fail("Test body limit must be positive");
        }
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
}
