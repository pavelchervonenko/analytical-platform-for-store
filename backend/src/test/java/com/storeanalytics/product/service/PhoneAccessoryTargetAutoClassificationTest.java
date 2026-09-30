package com.storeanalytics.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.product.model.ProductConditionType;
import com.storeanalytics.product.model.ProductSourceKind;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PhoneAccessoryTargetAutoClassificationTest {

    private final ProductAutoClassificationRuleEngine engine =
            new ProductAutoClassificationRuleEngine();

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "Чехол iPhone 16 Pro Milky Way Galaxy|CASE_APPLE_IPHONE",
        "Case айфон 15 Galaxy Blue|CASE_APPLE_IPHONE",
        "Защитное стекло iPhone 15 Galaxy|GLASS_IPHONE",
        "Защита камеры iPhone 16 Galaxy|GLASS_CAMERA_IPHONE",
        "Keephone X Crystal Samsung|CASE_SAMSUNG",
        "KEEPHONE X-Crystal Samsung|CASE_SAMSUNG",
        "Keephone X Crystal Самсунг|CASE_SAMSUNG",
        "Стекло А35 А36 А55 А56 S25FE|GLASS_SAMSUNG",
        "Защитное стекло А35|GLASS_SAMSUNG",
        "Стекло A36|GLASS_SAMSUNG",
        "Стекло А55|GLASS_SAMSUNG",
        "Стекло A56|GLASS_SAMSUNG",
        "Стекло S24FE|GLASS_SAMSUNG",
        "Стекло S25 FE|GLASS_SAMSUNG",
        "Стекло S24Ultra|GLASS_SAMSUNG",
        "Стекло S24 Plus|GLASS_SAMSUNG",
        "Защита камеры А35|GLASS_CAMERA_SAMSUNG",
        "Защитные линзы S24FE|GLASS_CAMERA_SAMSUNG",
        "Чехол А36|CASE_SAMSUNG",
        "Чехол S24FE|CASE_SAMSUNG",
        "Чехол Samsung Galaxy S24|CASE_SAMSUNG",
        "Чехол Galaxy S24|CASE_SAMSUNG",
        "Чехол iPad Galaxy|ACCESSORY_IPAD",
        "Чехол MacBook Galaxy|ACCESSORY_MAC",
        "Чехол AirPods Galaxy|ACCESSORY_PODS_WATCH",
        "Стекло Apple Watch|ACCESSORY_PODS_WATCH",
        "Чехол Uniq laptop 14|CASE_OTHER_DEVICE",
        "Чехол Keephone|OTHER_CASE",
        "Защита камеры Camera Film|GLASS_CAMERA_IPHONE",
        "Чехол XA35X|OTHER_CASE",
        "Чехол S25FEver|OTHER_CASE"
    })
    void resolvesAccessoryTargetWithoutChangingItsRole(String name, String category) {
        var result = engine.classify(name, ProductSourceKind.PRODUCT).orElseThrow();
        assertThat(result.categoryCode()).isEqualTo(category);
        assertThat(result.conditionType()).isEqualTo(ProductConditionType.NOT_APPLICABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Чехол iPhone 15 Samsung S24",
        "Чехол Samsung S24 iPhone 15",
        "Защитное стекло iPhone 15 / А35",
        "Защита камеры iPhone 15 / S24FE",
        "Чехол айфон 15 Самсунг",
        "Case iPhone 15 Galaxy S24",
        "Стекло Samsung для iPhone 15"
    })
    void doesNotPickPlatformOrFallThroughToPhoneForConflictingTargets(String name) {
        assertThat(engine.classify(name, ProductSourceKind.PRODUCT)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Keephone X Crystal Samsung",
        "Стекло А35",
        "Чехол iPhone 15 Samsung S24",
        "Защита камеры iPhone 15 S24FE"
    })
    void sourceWorkStillHasPriority(String name) {
        var result = engine.classify(name, ProductSourceKind.SERVICE).orElseThrow();
        assertThat(result.categoryCode()).isEqualTo("SETUP_SERVICE");
        assertThat(result.conditionType()).isEqualTo(ProductConditionType.NOT_APPLICABLE);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "Гарантия iPhone 15 Samsung S24|WARRANTY_GENERIC|NOT_APPLICABLE",
        "Установка стекла iPhone 15 Samsung S24|SETUP_SERVICE|NOT_APPLICABLE",
        "Кабель А35|CHARGER_CABLE|NOT_APPLICABLE",
        "Samsung S24 256GB New|SAMSUNG_NEW|NEW",
        "iPhone 15 128GB Б/У|IPHONE_USED|USED"
    })
    void preservesServicesChargersAndDevices(String name, String category,
                                           ProductConditionType condition) {
        var result = engine.classify(name, ProductSourceKind.PRODUCT).orElseThrow();
        assertThat(result.categoryCode()).isEqualTo(category);
        assertThat(result.conditionType()).isEqualTo(condition);
    }

    @ParameterizedTest
    @ValueSource(strings = {"А35", "S24FE", "Keephone X Crystal", "Keephone"})
    void doesNotInferPhoneOrCaseFromUnscopedShortLabels(String name) {
        assertThat(engine.classify(name, ProductSourceKind.PRODUCT)).isEmpty();
    }
}
