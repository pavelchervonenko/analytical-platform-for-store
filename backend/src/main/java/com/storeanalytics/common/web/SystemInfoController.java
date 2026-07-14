package com.storeanalytics.common.web;

import java.time.Instant;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemInfoController {

    private final Environment environment;

    public SystemInfoController(Environment environment) {
        this.environment = environment;
    }

    @GetMapping("/status")
    Map<String, Object> status() {
        return Map.of(
                "application", "store-analytics",
                "profiles", environment.getActiveProfiles(),
                "time", Instant.now().toString()
        );
    }
}
