package com.storeanalytics.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.product.model.ProductSourceKind;
import org.junit.jupiter.api.Test;

class XCrystalIphoneCaseAutoClassificationTest {

    private final ProductAutoClassificationRuleEngine engine =
            new ProductAutoClassificationRuleEngine();

    @Test
    void recognizesModelSpecificIphoneCasesWithoutIphoneWord() {
        assertCategory("Чехол Keephone X-Crystal 14 Pro Clear", "CASE_APPLE_IPHONE");
        assertCategory("Чехол Keephone X-Crystal 15 Pro Max прозрачный",
                "CASE_APPLE_IPHONE");
        assertCategory("Чехол Keephone X-Crystal 16 Clear", "CASE_APPLE_IPHONE");
        assertCategory("Чехол Keephone X-Crystal 17 Pro Max Blue",
                "CASE_APPLE_IPHONE");
    }

    @Test
    void doesNotInferIphoneFromBrandOrNumberAlone() {
        assertCategory("Чехол Keephone X-Crystal", "OTHER_CASE");
        assertCategory("Чехол Keephone X-Crystal Samsung S25 Clear", "CASE_SAMSUNG");
        assertCategory("Чехол Uniq для ноутбуков 14", "CASE_OTHER_DEVICE");
        assertCategory("Чехол Keephone X-Crystal iPad 11", "ACCESSORY_IPAD");
        assertCategory("Чехол AirPods Pro 3", "ACCESSORY_PODS_WATCH");
    }

    private void assertCategory(String name, String expected) {
        assertThat(engine.classify(name, ProductSourceKind.PRODUCT)
                .orElseThrow().categoryCode()).isEqualTo(expected);
    }
}
