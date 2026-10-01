package com.storeanalytics.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.product.model.ProductConditionType;
import com.storeanalytics.product.model.ProductSourceKind;
import org.junit.jupiter.api.Test;

class HeadphoneAutoClassificationTest {

    private final ProductAutoClassificationRuleEngine engine =
            new ProductAutoClassificationRuleEngine();

    @Test
    void separatesAppleSamsungAndOtherHeadphones() {
        assertCategory("AirPods Pro 3 New", "HEADPHONES_APPLE");
        assertCategory("Apple EarPods (Lightning) A1748", "HEADPHONES_APPLE");
        assertCategory("Samsung Galaxy Buds 4 Pro Black", "HEADPHONES_SAMSUNG");
        assertCategory("Marshall Major 5 Black", "HEADPHONES_OTHER");
        assertCategory("Sony WF-1000XM6 Black New", "HEADPHONES_OTHER");
        assertCategory("Sony WH-1000XM6 Silver New", "HEADPHONES_OTHER");
        assertCategory("Наушники Яндекс Дропс белые", "HEADPHONES_OTHER");
        assertCategory("Беспроводные наушники EW74", "HEADPHONES_OTHER");
        assertThat(engine.classify("Sony WH-1000XM6 Б/У", ProductSourceKind.PRODUCT)
                .orElseThrow().conditionType()).isEqualTo(ProductConditionType.USED);
    }

    @Test
    void keepsWatchesPhonesAndAccessoriesOutOfHeadphones() {
        assertCategory("Samsung Galaxy S26 New", "SAMSUNG_NEW");
        assertCategory("Samsung Galaxy Watch 8 New", "PODS_WATCH_OTHER_DEVICE");
        assertCategory("Apple Watch Series 11 New", "PODS_WATCH_OTHER_DEVICE");
        assertCategory("Чехол Samsung Galaxy Buds 4", "ACCESSORY_PODS_WATCH");
        assertCategory("EarPods ear tips", "ACCESSORY_PODS_WATCH");
        assertCategory("Амбушюры для наушников Sony", "OTHER_ACCESSORY_PRODUCT");
        assertThat(engine.classify("Диагностика Galaxy Buds", ProductSourceKind.SERVICE)
                .orElseThrow().categoryCode()).isEqualTo("SETUP_SERVICE");
    }

    private void assertCategory(String name, String expected) {
        assertThat(engine.classify(name, ProductSourceKind.PRODUCT)
                .orElseThrow().categoryCode()).isEqualTo(expected);
    }
}
