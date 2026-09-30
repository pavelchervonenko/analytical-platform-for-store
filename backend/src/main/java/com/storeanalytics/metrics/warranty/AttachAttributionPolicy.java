package com.storeanalytics.metrics.warranty;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AttachAttributionPolicy {
    private final boolean enabled;

    public AttachAttributionPolicy(@Value("${app.attach.attribution-enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }
}
