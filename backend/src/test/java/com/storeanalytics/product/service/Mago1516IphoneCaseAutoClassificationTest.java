package com.storeanalytics.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.product.model.ProductSourceKind;
import org.junit.jupiter.api.Test;

class Mago1516IphoneCaseAutoClassificationTest {

    private final ProductAutoClassificationRuleEngine engine =
            new ProductAutoClassificationRuleEngine();

    @Test
    void recognizesConfirmedIphoneModelsWithoutIphoneWord() {
        assertCategory("Чехол Keephone Mago Pro 15 Pro Max синий", "CASE_APPLE_IPHONE");
        assertCategory("Чехол Keephone Mago Pro Matte Magsafe 15 Pro Black",
                "CASE_APPLE_IPHONE");
        assertCategory("Чехол Keephone Mago Pro Matte Magsafe 16 Pro Max Titanium",
                "CASE_APPLE_IPHONE");
    }

    @Test
    void leavesUnspecifiedOrOtherDeviceCasesAlone() {
        assertCategory("Чехол Keephone Mago Pro Matte Magsafe", "OTHER_CASE");
        assertCategory("Чехол Keephone Mago Pro 15", "OTHER_CASE");
        assertCategory("Чехол Keephone Mago Pro Matte Magsafe iPad 11",
                "ACCESSORY_IPAD");
        assertCategory("Чехол Keephone Mago Pro Matte Magsafe Samsung S25",
                "CASE_SAMSUNG");
    }

    private void assertCategory(String name, String expected) {
        assertThat(engine.classify(name, ProductSourceKind.PRODUCT)
                .orElseThrow().categoryCode()).isEqualTo(expected);
    }
}
