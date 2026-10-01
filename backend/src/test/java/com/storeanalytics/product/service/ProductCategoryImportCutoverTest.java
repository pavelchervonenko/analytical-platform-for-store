package com.storeanalytics.product.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.product.model.ProductConditionType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductCategoryImportCutoverTest {
    @Test
    void rejectsHistoricalImportBeforeLookingUpOrCreatingProducts() {
        var service = new ProductCategoryImportService(null, null, null, null, null, null,
                new CatalogClassificationCutover("2026-10-02T00:00:00Z"));
        var command = new ProductCategoryImportCommand("livesklad-default",
                Instant.parse("2026-10-01T23:59:59Z"), "reviewed-v1", "prospective rollout",
                List.of(new ProductCategoryImportEntry("4310", "Cable", "CHARGER_CABLE",
                        ProductConditionType.NOT_APPLICABLE)));

        assertThatThrownBy(() -> service.importAssignments(command, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("activation boundary");
    }
}
