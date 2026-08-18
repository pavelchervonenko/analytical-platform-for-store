package com.storeanalytics.sync.service;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
record OrderSyncInfrastructure(
        ObjectMapper objectMapper,
        EntityManager entityManager,
        Clock clock,
        ZoneId businessZone
) {
}
