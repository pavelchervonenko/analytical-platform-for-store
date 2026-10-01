package com.storeanalytics.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.product.model.ProductSourceKind;
import org.junit.jupiter.api.Test;

class KeephoneNamedIphoneCaseAutoClassificationTest {

    private final ProductAutoClassificationRuleEngine engine =
            new ProductAutoClassificationRuleEngine();

    @Test
    void recognizesEveryRemainingModelNamedKeephoneIphoneCase() {
        for (String name : new String[]{
                "Чехол Keephone Armor Grip Magsafe 17 Pro Max Blue",
                "Чехол Keephone Glaze 17 Pro Max Clear",
                "Чехол Keephone Hybrid Pro 16 Clear",
                "Чехол Keephone Hybrid Pro 16 Pro Clear",
                "Чехол Keephone Hybrid Pro 17 Clear",
                "Чехол Keephone Magviar Magsafe 15 Pro Max черный",
                "Чехол Keephone Rosana silicone Magsafe 15 Pro Black",
                "Чехол Keephone Rosana silicone Magsafe 15 Pro Clay",
                "Чехол Keephone Rosana silicone Magsafe 15 Pro Max Blue",
                "Чехол Keephone Rosana silicone Magsafe 15 Pro Max Clay",
                "Чехол Keephone Rosana silicone Magsafe 15 Pro серый",
                "Чехол Keephone Rosana silicone Magsafe 15 Pro синий",
                "Чехол Keephone Kevlar Sunset MagSafe Case iPhone 17"
        }) {
            assertCategory(name, ProductSourceKind.PRODUCT, "CASE_APPLE_IPHONE");
        }
        assertCategory("Чехол Keephone Glaze 17 Pro Max Clear",
                ProductSourceKind.UNKNOWN, "CASE_APPLE_IPHONE");
    }

    @Test
    void doesNotTreatGenericOrOtherDeviceCasesAsIphoneCases() {
        assertCategory("Чехол Keephone Rosana silicone Magsafe",
                ProductSourceKind.PRODUCT, "OTHER_CASE");
        assertCategory("Чехол Keephone Hybrid Pro Magsafe",
                ProductSourceKind.PRODUCT, "OTHER_CASE");
        assertCategory("Чехол Keephone Hybrid Pro Samsung S26",
                ProductSourceKind.PRODUCT, "CASE_SAMSUNG");
        assertCategory("Чехол Keephone LENNO iPad 17",
                ProductSourceKind.PRODUCT, "ACCESSORY_IPAD");
        assertCategory("Чехол Keephone для Xiaomi 17",
                ProductSourceKind.PRODUCT, "CASE_OTHER_DEVICE");
        assertCategory("Чехол MacBook Keephone 16",
                ProductSourceKind.PRODUCT, "ACCESSORY_MAC");
    }

    private void assertCategory(String name, ProductSourceKind sourceKind, String expected) {
        assertThat(engine.classify(name, sourceKind)
                .orElseThrow().categoryCode()).isEqualTo(expected);
    }
}
