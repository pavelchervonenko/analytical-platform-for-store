package com.storeanalytics.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void treatsValidIncomingValueAsClientHintOnly() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationId.HEADER_NAME, "client-trace_123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            HttpServletRequest filteredRequest =
                    (HttpServletRequest) servletRequest;
            String requestId =
                    CorrelationId.getOrCreateRequestId(filteredRequest);

            assertThat(UUID.fromString(requestId)).isNotNull();
            assertThat(requestId).isNotEqualTo("client-trace_123");
            assertThat(CorrelationId.getClientHint(filteredRequest))
                    .isEqualTo("client-trace_123");
            assertThat(MDC.get(CorrelationId.REQUEST_ID_MDC_KEY))
                    .isEqualTo(requestId);
            assertThat(MDC.get(CorrelationId.CLIENT_HINT_MDC_KEY))
                    .isEqualTo("client-trace_123");
        });

        assertThat(response.getHeader(CorrelationId.HEADER_NAME))
                .isEqualTo(CorrelationId.getOrCreateRequestId(request))
                .isNotEqualTo("client-trace_123");
        assertThat(MDC.get(CorrelationId.REQUEST_ID_MDC_KEY)).isNull();
        assertThat(MDC.get(CorrelationId.CLIENT_HINT_MDC_KEY)).isNull();
    }

    @Test
    void ignoresInvalidClientHintWithoutReflectingIt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(
                CorrelationId.HEADER_NAME,
                "attacker\r\nX-Injected: true"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            HttpServletRequest filteredRequest =
                    (HttpServletRequest) servletRequest;

            assertThat(CorrelationId.getClientHint(filteredRequest)).isNull();
            assertThat(MDC.get(CorrelationId.CLIENT_HINT_MDC_KEY)).isNull();
        });

        String requestId = response.getHeader(CorrelationId.HEADER_NAME);
        assertThat(UUID.fromString(requestId)).isNotNull();
        assertThat(requestId).doesNotContain("attacker");
    }

    @Test
    void ignoresAmbiguousDuplicateClientHints() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationId.HEADER_NAME, "first-client-id");
        request.addHeader(CorrelationId.HEADER_NAME, "second-client-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            HttpServletRequest filteredRequest =
                    (HttpServletRequest) servletRequest;

            assertThat(CorrelationId.getClientHint(filteredRequest)).isNull();
            assertThat(MDC.get(CorrelationId.CLIENT_HINT_MDC_KEY)).isNull();
        });

        assertThat(UUID.fromString(
                response.getHeader(CorrelationId.HEADER_NAME)
        )).isNotNull();
    }

    @Test
    void createsDifferentRequestIdsForRepeatedClientHint() throws Exception {
        String first = filterRequest("stable-client-hint");
        String second = filterRequest("stable-client-hint");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void clearsBothMdcFieldsWhenDownstreamFails() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationId.HEADER_NAME, "client-hint");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(
                request,
                response,
                (servletRequest, servletResponse) -> {
                    throw new ServletException("downstream failure");
                }
        )).isInstanceOf(ServletException.class);

        assertThat(MDC.get(CorrelationId.REQUEST_ID_MDC_KEY)).isNull();
        assertThat(MDC.get(CorrelationId.CLIENT_HINT_MDC_KEY)).isNull();
    }

    private String filterRequest(String clientHint) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationId.HEADER_NAME, clientHint);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
        });

        return response.getHeader(CorrelationId.HEADER_NAME);
    }
}
