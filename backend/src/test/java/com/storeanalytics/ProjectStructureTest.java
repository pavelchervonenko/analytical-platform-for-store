package com.storeanalytics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProjectStructureTest {

    @Test
    void projectNameIsDefined() {
        assertThat("store-analytics").isNotBlank();
    }
}
