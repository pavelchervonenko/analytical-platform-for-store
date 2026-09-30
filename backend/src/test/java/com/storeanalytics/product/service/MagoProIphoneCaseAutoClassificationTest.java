package com.storeanalytics.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.product.model.ProductSourceKind;
import org.junit.jupiter.api.Test;

class MagoProIphoneCaseAutoClassificationTest {

    private final ProductAutoClassificationRuleEngine engine =
            new ProductAutoClassificationRuleEngine();

    @Test
    void recognizesIphone17ProAndProMaxCases() {
        assertCategory("Чехол Keephone Mago Pro Matte Magsafe 17 Pro Orange",
                "CASE_APPLE_IPHONE");
        assertCategory("Чехол Keephone Mago Pro Matte Magsafe 17 Pro Max Silver",
                "CASE_APPLE_IPHONE");
    }

    @Test
    void doesNotGeneralizeToOtherMagoCases() {
        assertCategory("Чехол Keephone Mago Pro Matte Magsafe", "OTHER_CASE");
        assertCategory("Чехол Keephone Mago Pro Matte Magsafe Samsung S25",
                "CASE_SAMSUNG");
        assertCategory("Чехол Keephone Mago Pro Matte Magsafe iPad 11",
                "ACCESSORY_IPAD");
    }

    private void assertCategory(String name, String expected) {
        assertThat(engine.classify(name, ProductSourceKind.PRODUCT)
                .orElseThrow().categoryCode()).isEqualTo(expected);
    }
}
