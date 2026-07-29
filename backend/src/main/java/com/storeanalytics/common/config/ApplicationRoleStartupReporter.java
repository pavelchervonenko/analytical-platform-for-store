package com.storeanalytics.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
class ApplicationRoleStartupReporter implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            ApplicationRoleStartupReporter.class
    );

    private final ApplicationRuntimeProperties properties;

    ApplicationRoleStartupReporter(ApplicationRuntimeProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        LOGGER.info("Application runtime role: {}", properties.role());
    }
}
