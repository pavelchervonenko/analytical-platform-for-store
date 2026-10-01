package com.storeanalytics.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.product.model.ProductConditionType;
import com.storeanalytics.product.model.ProductSourceKind;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class DeviceAnnotationAutoClassificationTest {

    private final ProductAutoClassificationRuleEngine engine =
            new ProductAutoClassificationRuleEngine();

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "iPhone 12 64GB Blue Б/У - ремонт|IPHONE_USED|USED",
        "iPhone 14 Pro 256GB Black (A) 80% Б/У - РЕМОНТ|IPHONE_USED|USED",
        "iPhone 13 Mini 128GB Blue Б/У — ремонт|IPHONE_USED|USED",
        "Apple iPhone 15 128GB Green ASIS - ремонт|IPHONE_NEW_ASIS|ASIS",
        "айфон 13 128GB Б/У - ремонт|IPHONE_USED|USED",
        "iPhone 16 256GB New - repair|IPHONE_NEW_ASIS|NEW",
        "MacBook Air 13 M4 16/256 New БРАК ПО ГАРАНТИИ ЛЕЖИТ|IPAD_MAC|NEW",
        "Apple MacBook Pro 14 M3 16/512 Б/У брак по гарантии|IPAD_MAC|USED",
        "MacBook Air 15 M3 16/256 ASIS - ремонт|IPAD_MAC|ASIS"
    })
    void keepsDeviceCategoryAndExplicitCondition(String name, String category,
                                                ProductConditionType condition) {
        var decision = engine.classify(name, ProductSourceKind.PRODUCT).orElseThrow();
        assertThat(decision.categoryCode()).isEqualTo(category);
        assertThat(decision.conditionType()).isEqualTo(condition);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "Ремонт iPhone 13|SETUP_SERVICE",
        "iPhone 15 - ремонт|SETUP_SERVICE",
        "MacBook Air 13 - ремонт|SETUP_SERVICE",
        "Гарантия MacBook Air 13|WARRANTY_GENERIC",
        "Расширенная гарантия iPhone 15|WARRANTY_GENERIC",
        "Premium iPhone 15|PREMIUM_PROTECTION",
        "iPhone 15 Premium - ремонт|PREMIUM_PROTECTION",
        "iPhone 15 + гарантия - ремонт|WARRANTY_GENERIC",
        "iPhone 15 комплект - ремонт|SETUP_SERVICE",
        "iPhone 15 набор - ремонт|SETUP_SERVICE",
        "Настройка iPhone 15 - ремонт|SETUP_SERVICE",
        "Чехол iPhone 15 - ремонт|SETUP_SERVICE",
        "iPhone 15 чехол - ремонт|SETUP_SERVICE",
        "MacBook Air 13 адаптер - ремонт|SETUP_SERVICE",
        "iPhone 15 ремонт дисплея|SETUP_SERVICE",
        "iPhone 15 гарантия 12 месяцев|WARRANTY_GENERIC"
    })
    void doesNotExpandExceptionToServicesAccessoriesOrBundles(String name, String category) {
        // Out-of-scope ambiguous names retain their previous result, not a device assignment.
        assertThat(engine.classify(name, ProductSourceKind.PRODUCT).orElseThrow().categoryCode())
                .isEqualTo(category);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SERVICE", "UNKNOWN"})
    void requiresConfirmedProductSourceKind(ProductSourceKind kind) {
        var repair = engine.classify("iPhone 12 64GB Б/У - ремонт", kind).orElseThrow();
        assertThat(repair.categoryCode()).isEqualTo("SETUP_SERVICE");
        assertThat(repair.conditionType()).isEqualTo(ProductConditionType.NOT_APPLICABLE);
        var warranty = engine.classify("MacBook Air 13 New БРАК ПО ГАРАНТИИ ЛЕЖИТ", kind)
                .orElseThrow();
        assertThat(warranty.categoryCode()).isEqualTo("WARRANTY_GENERIC");
        assertThat(warranty.conditionType()).isEqualTo(ProductConditionType.NOT_APPLICABLE);
    }
}
