package com.storeanalytics.common.web;

import java.time.Instant;

public record SystemStatusView(
        String application,
        String version,
        Instant time
) {
}
