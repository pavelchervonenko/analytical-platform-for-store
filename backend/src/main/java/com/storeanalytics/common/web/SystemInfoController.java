package com.storeanalytics.common.web;

import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemInfoController {

    private static final String APPLICATION_NAME = "store-analytics";

    private final ObjectProvider<BuildProperties> buildPropertiesProvider;
    private final Clock clock;

    public SystemInfoController(
            ObjectProvider<BuildProperties> buildPropertiesProvider,
            Clock clock
    ) {
        this.buildPropertiesProvider = buildPropertiesProvider;
        this.clock = clock;
    }

    @GetMapping("/status")
    SystemStatusView status() {
        BuildProperties build = buildPropertiesProvider.getIfAvailable();
        return new SystemStatusView(
                APPLICATION_NAME,
                build == null ? "development" : build.getVersion(),
                ApiContractVersion.CURRENT,
                Instant.now(clock)
        );
    }
}
