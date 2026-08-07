package com.storeanalytics.common.database;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExpectedSchemaVersionTest {

    @Test
    void resolvesLatestVersionFromPackagedMigrations() {
        assertThat(new ExpectedSchemaVersion().value()).isEqualTo("32");
    }
}
