package com.storeanalytics.common.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

@Component
public class BackendRequestMetricsInterceptor implements HandlerInterceptor {

    static final String DURATION_METRIC = "storeanalytics.backend.request.duration";
    private static final String SAMPLE_ATTRIBUTE =
            BackendRequestMetricsInterceptor.class.getName() + ".sample";

    private final MeterRegistry meterRegistry;

    public BackendRequestMetricsInterceptor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) {
        request.setAttribute(SAMPLE_ATTRIBUTE, Timer.start(meterRegistry));
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception exception
    ) {
        Object sampleValue = request.getAttribute(SAMPLE_ATTRIBUTE);
        Object patternValue = request.getAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE
        );
        if (!(sampleValue instanceof Timer.Sample sample)
                || !(patternValue instanceof String pattern)) {
            return;
        }
        String area = area(pattern);
        if (area == null) {
            return;
        }
        sample.stop(Timer.builder(DURATION_METRIC)
                .description("Duration of KPI, payroll and report HTTP requests")
                .tag("area", area)
                .tag("method", request.getMethod().toLowerCase(Locale.ROOT))
                .tag("outcome", outcome(response.getStatus()))
                .publishPercentileHistogram()
                .register(meterRegistry));
    }

    private String area(String pattern) {
        if (pattern.contains("/kpi")) {
            return "kpi";
        }
        if (pattern.contains("/payroll")) {
            return "payroll";
        }
        if (pattern.contains("/reports")) {
            return "report";
        }
        return null;
    }

    private String outcome(int status) {
        if (status >= 500) {
            return "server_error";
        }
        if (status >= 400) {
            return "client_error";
        }
        return "success";
    }
}
