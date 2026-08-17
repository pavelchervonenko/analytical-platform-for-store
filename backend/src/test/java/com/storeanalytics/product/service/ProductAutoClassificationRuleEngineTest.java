package com.storeanalytics.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.product.model.ProductConditionType;
import com.storeanalytics.product.model.ProductSourceKind;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ProductAutoClassificationRuleEngineTest {

    private final ProductAutoClassificationRuleEngine engine =
            new ProductAutoClassificationRuleEngine();

    @ParameterizedTest
    @MethodSource("productionDryRunCases")
    void classifiesApprovedProductionDryRun(
            String name,
            String expectedCategory,
            ProductConditionType expectedCondition
    ) {
        var decision = engine.classify(name, ProductSourceKind.PRODUCT);

        assertThat(decision).isPresent();
        assertThat(decision.orElseThrow().categoryCode())
                .isEqualTo(expectedCategory);
        assertThat(decision.orElseThrow().conditionType())
                .isEqualTo(expectedCondition);
    }

    @Test
    void leavesUnknownProductUnmapped() {
        assertThat(engine.classify(
                "Новый товар без классификационных признаков",
                ProductSourceKind.PRODUCT
        )).isEmpty();
    }

    @Test
    void classifiesUnknownServiceBySourceKind() {
        var decision = engine.classify(
                "Работа специалиста",
                ProductSourceKind.SERVICE
        );

        assertThat(decision).isPresent();
        assertThat(decision.orElseThrow().categoryCode())
                .isEqualTo("SETUP_SERVICE");
    }

    private static Stream<Arguments> productionDryRunCases() {
        return Stream.of(
                arguments(
                        "Apple Watch S11 42mm Rose Gold SB M/L New",
                        "PODS_WATCH_OTHER_DEVICE",
                        ProductConditionType.NEW
                ),
                arguments(
                        "IPad Air 11 M2 chip 128GB Space Gray (A) 97% Б/У",
                        "IPAD_MAC",
                        ProductConditionType.USED
                ),
                arguments(
                        "Samsung Galaxy A37 8/128 Graygreen New",
                        "SAMSUNG_NEW",
                        ProductConditionType.NEW
                ),
                arguments(
                        "Samsung Galaxy S24 Ultra 12/256Gb Black (A) Б/У",
                        "SAMSUNG_USED",
                        ProductConditionType.USED
                ),
                arguments(
                        "Samsung Galaxy S26 12/256Gb White new",
                        "SAMSUNG_NEW",
                        ProductConditionType.NEW
                ),
                arguments(
                        "iPhone 12 Pro 128GB Pacific Blue (B) 100% Б/У",
                        "IPHONE_USED",
                        ProductConditionType.USED
                ),
                arguments(
                        "iPhone 16 Pro Max 256GB Desert Titanium (A) 99% Б/У",
                        "IPHONE_USED",
                        ProductConditionType.USED
                ),
                arguments(
                        "iPhone 15 Pro 128GB Black Titanium VC/A Asis+",
                        "IPHONE_NEW_ASIS",
                        ProductConditionType.ASIS
                ),
                arguments(
                        "Защита камер Keephone Persmo Iphone 17 Pro Max Clear",
                        "GLASS_CAMERA_IPHONE",
                        ProductConditionType.NOT_APPLICABLE
                ),
                arguments(
                        "Защитное стекло Remax Iphone 15 Pro прозрачное",
                        "GLASS_IPHONE",
                        ProductConditionType.NOT_APPLICABLE
                ),
                arguments(
                        "Защитное стекло SupGLASS SG-13 17 Pro матовое",
                        "GLASS_IPHONE",
                        ProductConditionType.NOT_APPLICABLE
                ),
                arguments(
                        "Защитное стекло Remax GL27 Samsung S24/S25",
                        "GLASS_SAMSUNG",
                        ProductConditionType.NOT_APPLICABLE
                ),
                arguments(
                        "Защитное стекло на камеры Keephone Camera Lens S26 Ultra Clear",
                        "GLASS_CAMERA_SAMSUNG",
                        ProductConditionType.NOT_APPLICABLE
                ),
                arguments(
                        "Защита Kaмеры Keephone Samsung",
                        "GLASS_CAMERA_SAMSUNG",
                        ProductConditionType.NOT_APPLICABLE
                ),
                arguments(
                        "Защитные линзы SupGLASS 15 Pro/15ProMax Colorless",
                        "GLASS_CAMERA_IPHONE",
                        ProductConditionType.NOT_APPLICABLE
                ),
                arguments(
                        "Кабель ACEFAST C18-03 USB-C to USB-C 1.2m White",
                        "CHARGER_CABLE",
                        ProductConditionType.NOT_APPLICABLE
                ),
                arguments(
                        "СЗУ Apple Power Adapter 30W Original",
                        "CHARGER_CABLE",
                        ProductConditionType.NOT_APPLICABLE
                ),
                arguments(
                        "Чехол VLP Aster Pro Case iPhone 17 Pro Max Белый",
                        "CASE_APPLE_IPHONE",
                        ProductConditionType.NOT_APPLICABLE
                ),
                arguments(
                        "Apple Pencil Pro NEW",
                        "IPAD_MAC",
                        ProductConditionType.NEW
                ),
                arguments(
                        "Стилус Apple Pencil Pro NEW",
                        "IPAD_MAC",
                        ProductConditionType.NEW
                ),
                arguments(
                        "Apple Magic Mouse USB-C Black",
                        "IPAD_MAC",
                        ProductConditionType.NEW
                ),
                arguments(
                        "Magic Keyboard iPad Pro Black",
                        "IPAD_MAC",
                        ProductConditionType.NEW
                ),
                arguments(
                        "Клавиатура Magic Keyboard iPad Pro Black",
                        "IPAD_MAC",
                        ProductConditionType.NEW
                ),
                arguments(
                        "PlayStation 5 Dualsense Midnight Black",
                        "PODS_WATCH_OTHER_DEVICE",
                        ProductConditionType.NEW
                ),
                arguments(
                        "iPhone Air Magsafe Battery Pack",
                        "CHARGER_CABLE",
                        ProductConditionType.NOT_APPLICABLE
                ),
                arguments(
                        "Наконечники Elago Metal Tips для Apple Pencil 1/2/Pro/USB-C (2шт.)",
                        "ACCESSORY_IPAD_MAC",
                        ProductConditionType.NOT_APPLICABLE
                ),
                arguments(
                        "Наконечники для универсального стилуса",
                        "OTHER_ACCESSORY_PRODUCT",
                        ProductConditionType.NOT_APPLICABLE
                ),
                arguments(
                        "Док-станция PS5 DualSense ChargingStation",
                        "OTHER_ACCESSORY_PRODUCT",
                        ProductConditionType.NOT_APPLICABLE
                )
        );
    }

    private static Arguments arguments(
            String name,
            String category,
            ProductConditionType condition
    ) {
        return Arguments.of(name, category, condition);
    }
}
