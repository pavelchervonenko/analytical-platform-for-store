package com.storeanalytics.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.product.model.ProductSourceKind;
import org.junit.jupiter.api.Test;

class DeviceAccessorySplitAutoClassificationTest {

    private final ProductAutoClassificationRuleEngine engine =
            new ProductAutoClassificationRuleEngine();

    @Test
    void separatesIpadMacAndExplicitOtherDeviceCases() {
        assertCategory("Чехол для iPad Pro 13", "ACCESSORY_IPAD");
        assertCategory("Защитное стекло iPad Air 11", "ACCESSORY_IPAD");
        assertCategory("Наконечники для Apple Pencil", "ACCESSORY_IPAD");
        assertCategory("Чехол MacBook Keephone SMOKY MATTE", "ACCESSORY_MAC");
        assertCategory("Клавиатура для MacBook", "ACCESSORY_MAC");
        assertCategory("Чехол Uniq для ноутбуков 14", "CASE_OTHER_DEVICE");
        assertCategory("Чехол для планшета Lenovo", "CASE_OTHER_DEVICE");
        assertCategory("Чехол Google Pixel 9", "CASE_OTHER_DEVICE");
    }

    @Test
    void preservesPhonePodsAndAmbiguousCaseBoundaries() {
        assertCategory("Чехол iPhone 17 Pro", "CASE_APPLE_IPHONE");
        assertCategory("Чехол Samsung S25", "CASE_SAMSUNG");
        assertCategory("Чехол AirPods Pro 3", "ACCESSORY_PODS_WATCH");
        assertCategory("Чехол Keephone X-Crystal", "OTHER_CASE");
        assertCategory("Защитная пленка для планшета", "OTHER_ACCESSORY_PRODUCT");
    }

    private void assertCategory(String name, String expected) {
        assertThat(engine.classify(name, ProductSourceKind.PRODUCT)
                .orElseThrow().categoryCode()).isEqualTo(expected);
    }
}
