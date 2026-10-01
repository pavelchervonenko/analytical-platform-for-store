package com.storeanalytics.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.product.model.ProductSourceKind;
import org.junit.jupiter.api.Test;

class CameraGlassAutoClassificationTest {

    private final ProductAutoClassificationRuleEngine engine =
            new ProductAutoClassificationRuleEngine();

    @Test
    void separatesCameraAndDisplayProtectionByPhoneBrand() {
        assertCategory("Защитное стекло для камеры VLP iPhone Air",
                "GLASS_CAMERA_IPHONE");
        assertCategory("Защитные линзы Camera Film", "GLASS_CAMERA_IPHONE");
        assertCategory("Защитное стекло на камеру iPhone 17 Pro",
                "GLASS_CAMERA_IPHONE");
        assertCategory("Защита камер Keephone Samsung S25 Ultra",
                "GLASS_CAMERA_SAMSUNG");
        assertCategory("Защитное стекло Samsung S25 Ultra", "GLASS_SAMSUNG");
        assertCategory("Защитное стекло iPhone 17 Pro", "GLASS_IPHONE");
    }

    private void assertCategory(String name, String expected) {
        assertThat(engine.classify(name, ProductSourceKind.PRODUCT)
                .orElseThrow().categoryCode()).isEqualTo(expected);
    }
}
