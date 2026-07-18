package com.storeanalytics.common.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
    @Bean
    ZoneId businessZone() {
        return ZoneId.of("Europe/Kaliningrad");
    }
}
