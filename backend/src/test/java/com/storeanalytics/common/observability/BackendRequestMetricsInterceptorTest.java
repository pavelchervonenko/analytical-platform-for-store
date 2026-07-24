package com.storeanalytics.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

class BackendRequestMetricsInterceptorTest {

    @Test
    void recordsLowCardinalityKpiRequestMetrics() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BackendRequestMetricsInterceptor interceptor =
                new BackendRequestMetricsInterceptor(registry);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/stores/1/kpi"
        );
        request.setAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                "/api/stores/{storeId}/kpi"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        assertThat(registry.get(
                        BackendRequestMetricsInterceptor.DURATION_METRIC
                )
                .tag("area", "kpi")
                .tag("method", "get")
                .tag("outcome", "success")
                .timer()
                .count()).isEqualTo(1);
    }

    @Test
    void recordsLowCardinalityReportRequestMetrics() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BackendRequestMetricsInterceptor interceptor =
                new BackendRequestMetricsInterceptor(registry);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/stores/1/reports/2"
        );
        request.setAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                "/api/stores/{storeId}/reports/{reportId}"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        assertThat(registry.get(
                        BackendRequestMetricsInterceptor.DURATION_METRIC
                )
                .tag("area", "report")
                .tag("method", "get")
                .tag("outcome", "success")
                .timer()
                .count()).isEqualTo(1);
    }

    @Test
    void ignoresUnrelatedApiRequests() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BackendRequestMetricsInterceptor interceptor =
                new BackendRequestMetricsInterceptor(registry);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/stores"
        );
        request.setAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                "/api/stores"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        assertThat(registry.find(
                BackendRequestMetricsInterceptor.DURATION_METRIC
        ).timer()).isNull();
    }
}
