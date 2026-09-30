package com.storeanalytics.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.product.model.ProductSourceKind;
import org.junit.jupiter.api.Test;

class ExplicitIphoneCaseAutoClassificationTest {

    private final ProductAutoClassificationRuleEngine engine =
            new ProductAutoClassificationRuleEngine();

    @Test
    void recognizesExplicitAndUnambiguousModelNamedCases() {
        assertCategory("Чехол Uniq Magsafe 17 Pro Combat", "CASE_APPLE_IPHONE");
        assertCategory("Чехол Uniq Magsafe 17 Pro Max Lyden", "CASE_APPLE_IPHONE");
        assertCategory("Чехол LUXO 13 Pro Magsafe синий", "CASE_APPLE_IPHONE");
        assertCategory("Чехол прозрачный 16 plus", "CASE_APPLE_IPHONE");
        assertCategory("Чехол прозрачный 16e", "CASE_APPLE_IPHONE");
        assertCategory("Чехол VLP Aster Pro Case с MagSafe 17 Pro", "CASE_APPLE_IPHONE");
        assertCategory("Чехол Airity Dragon Armor iPhone 18 Pro Max",
                "CASE_APPLE_IPHONE");
        assertThat(engine.classify("Чехол прозрачный 16e", ProductSourceKind.UNKNOWN)
                .orElseThrow().categoryCode()).isEqualTo("CASE_APPLE_IPHONE");
    }

    @Test
    void excludesAmbiguousBundlesAndOtherDevices() {
        assertCategory("Комплект чехол+стекло 13 Mini", "OTHER_ACCESSORY_PRODUCT");
        assertCategory("Чехол Baseus Crystal", "OTHER_CASE");
        assertCategory("Чехол Uniq для ноутбуков 14", "CASE_OTHER_DEVICE");
        assertCategory("Чехол Keephone для iPad Pro 13", "ACCESSORY_IPAD");
        assertCategory("Чехол Samsung S25 Ultra", "CASE_SAMSUNG");
        assertCategory("Чехол для Xiaomi 17 Pro", "CASE_OTHER_DEVICE");
    }

    private void assertCategory(String name, String expected) {
        assertThat(engine.classify(name, ProductSourceKind.PRODUCT)
                .orElseThrow().categoryCode()).isEqualTo(expected);
    }
}
