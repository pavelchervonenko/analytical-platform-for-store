package com.storeanalytics.common.config;

import com.storeanalytics.common.web.ApiContractVersion;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiContractConfiguration {

    @Bean
    OpenAPI storeAnalyticsOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Store Analytics API")
                .version(ApiContractVersion.CURRENT));
    }
}
