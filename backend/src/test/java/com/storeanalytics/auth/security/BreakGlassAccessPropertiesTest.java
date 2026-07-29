package com.storeanalytics.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BreakGlassAccessPropertiesTest {

    @Test
    void defaultsToNoEmergencyAccounts() {
        assertThat(new BreakGlassAccessProperties(null).userIds()).isEmpty();
    }

    @Test
    void createsAnImmutableSnapshotOfConfiguredIdentifiers() {
        UUID userId = UUID.randomUUID();
        Set<UUID> source = new LinkedHashSet<>();
        source.add(userId);

        BreakGlassAccessProperties properties =
                new BreakGlassAccessProperties(source);
        source.clear();

        assertThat(properties.contains(userId)).isTrue();
        assertThat(properties.userIds()).containsExactly(userId);
    }
}
