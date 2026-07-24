package com.storeanalytics.common.observability;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ObservabilityWebConfiguration implements WebMvcConfigurer {

    private final BackendRequestMetricsInterceptor requestMetricsInterceptor;

    public ObservabilityWebConfiguration(
            BackendRequestMetricsInterceptor requestMetricsInterceptor
    ) {
        this.requestMetricsInterceptor = requestMetricsInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(requestMetricsInterceptor).addPathPatterns("/api/**");
    }
}
