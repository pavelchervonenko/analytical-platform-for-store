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
    @MethodSource({
        "speakerCases",
        "smartGlassesCases",
        "fitnessWearableCases",
        "cameraCases",
        "productionDryRunCases",
        "approvedChargingCases",
        "powerBankCases",
        "adapterCases",
        "confirmedAmbiguousChargingCases",
        "septemberMobiSphereCases",
        "legacyUnmappedRegressionCases",
        "customerMethodologyCases"
    })
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

    @ParameterizedTest
    @MethodSource("autoInstallationGlassCases")
    void distinguishesGlassApplicatorsFromInstallationWorks(
            String name, String expectedCategory
    ) {
        for (var sourceKind : new ProductSourceKind[] {
                ProductSourceKind.PRODUCT, ProductSourceKind.UNKNOWN
        }) {
            assertThat(engine.classify(name, sourceKind).orElseThrow().categoryCode())
                    .as("%s: %s", sourceKind, name).isEqualTo(expectedCategory);
        }
        assertThat(engine.classify(name, ProductSourceKind.SERVICE)
                .orElseThrow().categoryCode()).isEqualTo("SETUP_SERVICE");
    }

    private static Stream<Arguments> autoInstallationGlassCases() {
        return Stream.of(
                Arguments.of(
                        "Защитное стекло Глазурь Автоустановка Глянец iPhone 17 Pro / 18 Pro",
                        "GLASS_IPHONE"),
                Arguments.of("Защитное стекло Samsung S25 автоустановка", "GLASS_SAMSUNG"),
                Arguments.of("Защитное стекло iPhone с автоустановкой", "GLASS_IPHONE"),
                Arguments.of("Установка защитного стекла iPhone", "SETUP_SERVICE"),
                Arguments.of("Переустановка ПО iPhone", "SETUP_SERVICE"),
                Arguments.of("Автоустановка программ iPhone", "SETUP_SERVICE"),
                Arguments.of(
                        "Защитное стекло iPhone Автоустановка + установка",
                        "SETUP_SERVICE"),
                Arguments.of(
                        "Защитное стекло iPhone Автоустановка + переустановка ПО",
                        "SETUP_SERVICE"),
                Arguments.of(
                        "Защитное стекло iPhone + автоустановка программ",
                        "SETUP_SERVICE"),
                Arguments.of("Набор стекол iPhone Автоустановка", "SETUP_SERVICE"),
                Arguments.of("Ремонт стекла iPhone Автоустановка", "SETUP_SERVICE")
        );
    }

    @Test
    void leavesUnknownProductUnmapped() {
        assertThat(engine.classify(
                "Новый товар без классификационных признаков",
                ProductSourceKind.PRODUCT
        )).isEmpty();
        assertThat(engine.classify(
                "Phone 15 stand",
                ProductSourceKind.PRODUCT
        )).isEmpty();
        assertThat(engine.classify(
                "Instax Mini 13 film",
                ProductSourceKind.PRODUCT
        )).isEmpty();
        assertThat(engine.classify(
                "Плёнка Instax Mini 13",
                ProductSourceKind.PRODUCT
        )).isEmpty();
        assertThat(engine.classify(
                "iPhone 15 Pro пароль восстановлен",
                ProductSourceKind.PRODUCT
        ).orElseThrow().categoryCode()).isEqualTo("IPHONE_NEW_ASIS");
    }

    @Test
    void keepsChargingRulesAwayFromPhonesAndNonChargingAdapters() {
        assertThat(engine.classify(
                "Samsung Galaxy S25 256GB",
                ProductSourceKind.PRODUCT
        ).orElseThrow().categoryCode()).isEqualTo("SAMSUNG_NEW");
        assertThat(engine.classify(
                "Адаптер VLP Infinity USB-C Hub 5 в 1 Графит",
                ProductSourceKind.PRODUCT
        ).orElseThrow().categoryCode()).isEqualTo("OTHER_ACCESSORY_PRODUCT");
        assertThat(engine.classify(
                "No Box Apple HDMI Cable",
                ProductSourceKind.PRODUCT
        )).isEmpty();
    }

    @Test
    void keepsRayBanAccessoryAndWorkOutOfSmartGlasses() {
        assertThat(engine.classify(
                "Чехол Ray Ban Meta",
                ProductSourceKind.PRODUCT
        ).orElseThrow().categoryCode()).isEqualTo("OTHER_ACCESSORY_PRODUCT");
        assertThat(engine.classify(
                "Настройка Ray Ban Meta",
                ProductSourceKind.SERVICE
        ).orElseThrow().categoryCode()).isEqualTo("SETUP_SERVICE");
    }

    @Test
    void classifiesPasswordRecoverySoldAsProductAsSetupService() {
        var decision = engine.classify(
                "Восстановления паролей",
                ProductSourceKind.PRODUCT
        ).orElseThrow();
        assertThat(decision.categoryCode()).isEqualTo("SETUP_SERVICE");
        assertThat(decision.conditionType()).isEqualTo(ProductConditionType.NOT_APPLICABLE);
        assertThat(decision.ruleId()).isEqualTo("password-recovery-service");
    }

    @Test
    void keepsInstaxCaseOutOfCameras() {
        assertThat(engine.classify(
                "Чехол для Instax Mini 13",
                ProductSourceKind.PRODUCT
        ).orElseThrow().categoryCode()).isEqualTo("OTHER_ACCESSORY_PRODUCT");
    }

    @ParameterizedTest
    @MethodSource("sourceKindServiceCases")
    void classifiesSourceWorkAsServiceBeforeAccessoryOrDeviceRules(String name) {
        var decision = engine.classify(
                name,
                ProductSourceKind.SERVICE
        );

        assertThat(decision).isPresent();
        assertThat(decision.orElseThrow().categoryCode())
                .isEqualTo("SETUP_SERVICE");
        assertThat(decision.orElseThrow().conditionType())
                .isEqualTo(ProductConditionType.NOT_APPLICABLE);
        assertThat(decision.orElseThrow().ruleId())
                .isEqualTo("source-kind-service");
    }

    @Test
    void keepsSpecificCommercialServiceRuleAheadOfSourceKindFallback() {
        var decision = engine.classify(
                "Расширенная гарантия Future Store",
                ProductSourceKind.SERVICE
        );

        assertThat(decision).isPresent();
        assertThat(decision.orElseThrow().categoryCode())
                .isEqualTo("WARRANTY_GENERIC");
        assertThat(decision.orElseThrow().ruleId())
                .isEqualTo("warranty");
    }

    @Test
    void classifiesWiredAppleEarPodsAsAppleHeadphones() {
        var decision = engine.classify(
                "Apple EarPods (Lightning) A1748",
                ProductSourceKind.PRODUCT
        );

        assertThat(decision).isPresent();
        assertThat(decision.orElseThrow().categoryCode())
                .isEqualTo("HEADPHONES_APPLE");
        assertThat(decision.orElseThrow().conditionType())
                .isEqualTo(ProductConditionType.NEW);
    }

    @Test
    void treatsSpeakerRepairAsService() {
        var decision = engine.classify(
                "Ремонт колонки JBL",
                ProductSourceKind.PRODUCT
        );

        assertThat(decision).isPresent();
        assertThat(decision.orElseThrow().categoryCode())
                .isEqualTo("SETUP_SERVICE");
    }

    @Test
    void keepsHeadphonesOutOfSpeakerCategory() {
        var decision = engine.classify(
                "Наушники JBL Tune 770NC Black",
                ProductSourceKind.PRODUCT
        );

        assertThat(decision).isPresent();
        assertThat(decision.orElseThrow().categoryCode())
                .isEqualTo("HEADPHONES_OTHER");
    }

    private static Stream<Arguments> sourceKindServiceCases() {
        return Stream.of(
                Arguments.of("Работа специалиста"),
                Arguments.of("Замена заднего стекла IPhone"),
                Arguments.of("Замена стекла дисплея"),
                Arguments.of("Замена стекла на камеру"),
                Arguments.of("Чистка тач-пада и клавиатуры с разборкой"),
                Arguments.of("ЗАМЕНА ДИСПЛЕЯ 13 АЙФОНА"),
                Arguments.of("Диагностика колонки JBL")
        );
    }

    private static Stream<Arguments> speakerCases() {
        return Stream.of(
                arguments(
                        "Яндекс Станция Макс бежевый",
                        "SPEAKERS",
                        ProductConditionType.NEW
                ),
                arguments(
                        "Yandex Station Max б/у",
                        "SPEAKERS",
                        ProductConditionType.USED
                ),
                arguments("Колонка JBL Charge 6 Black", "SPEAKERS", ProductConditionType.NEW),
                arguments("JBL Flip 7 Blue New", "SPEAKERS", ProductConditionType.NEW),
                arguments("Harman Kardon Onyx Studio 9 Black New", "SPEAKERS", ProductConditionType.NEW),
                arguments("Bluetooth speaker used", "SPEAKERS", ProductConditionType.USED)
        );
    }

    private static Stream<Arguments> smartGlassesCases() {
        return Stream.of(
                arguments(
                        "Ray Ban Meta Starfire Kylie Black/Clear to Grey Transition",
                        "SMART_GLASSES",
                        ProductConditionType.NEW
                ),
                arguments(
                        "Ray-Ban Meta Wayfarer Shiny Black",
                        "SMART_GLASSES",
                        ProductConditionType.NEW
                ),
                arguments(
                        "Meta Ray Ban Wayfarer Black Б/У",
                        "SMART_GLASSES",
                        ProductConditionType.USED
                ),
                arguments(
                        "Ray Ban Wayfarer Matte Black Transitions Grey (M) NEW",
                        "SMART_GLASSES",
                        ProductConditionType.NEW
                ),
                arguments(
                        "Ray-Ban Wayfarer Shiny Black",
                        "SMART_GLASSES",
                        ProductConditionType.NEW
                ),
                arguments(
                        "Rayban Wayfarer Black Б/У",
                        "SMART_GLASSES",
                        ProductConditionType.USED
                )
        );
    }

    private static Stream<Arguments> cameraCases() {
        return Stream.of(
                arguments("Instax Mini 13 Pink", "CAMERAS", ProductConditionType.NEW),
                arguments("Instax Mini 13 White", "CAMERAS", ProductConditionType.NEW),
                arguments("Instax Mini 13 Black Б/У", "CAMERAS", ProductConditionType.USED)
        );
    }

    private static Stream<Arguments> fitnessWearableCases() {
        return Stream.of(
                arguments("Garmin Forerunner 165 Music Whitestone", "FITNESS_WEARABLE", ProductConditionType.NEW),
                arguments("Garmin Vivoactive 6 Slate with Black Band", "FITNESS_WEARABLE", ProductConditionType.NEW),
                arguments("Браслет Google Fitbit Air Berry", "FITNESS_WEARABLE", ProductConditionType.NEW),
                arguments("Браслет Google Fitbit Air Lavender", "FITNESS_WEARABLE", ProductConditionType.NEW),
                arguments("Браслет Google Fitbit Air Obsidian", "FITNESS_WEARABLE", ProductConditionType.NEW),
                arguments("Браслет Whoop 5.0 Life", "FITNESS_WEARABLE", ProductConditionType.NEW),
                arguments("Браслет Whoop 5.0 One", "FITNESS_WEARABLE", ProductConditionType.NEW),
                arguments("Браслет Whoop 5.0 Peak", "FITNESS_WEARABLE", ProductConditionType.NEW),
                arguments("Браслет Whoop Life 5.0", "FITNESS_WEARABLE", ProductConditionType.NEW),
                arguments("Ремешок Whoop 5.0", "OTHER_ACCESSORY_PRODUCT", ProductConditionType.NOT_APPLICABLE),
                arguments(
                        "Google Fitbit Air Active Band",
                        "OTHER_ACCESSORY_PRODUCT",
                        ProductConditionType.NOT_APPLICABLE
                )
        );
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
                        "ACCESSORY_IPAD",
                        ProductConditionType.NOT_APPLICABLE
                ),
                arguments(
                        "Стилус Apple Pencil Pro NEW",
                        "ACCESSORY_IPAD",
                        ProductConditionType.NOT_APPLICABLE
                ),
                arguments(
                        "Apple Magic Mouse USB-C Black",
                        "ACCESSORY_MAC",
                        ProductConditionType.NOT_APPLICABLE
                ),
                arguments(
                        "Magic Keyboard iPad Pro Black",
                        "ACCESSORY_IPAD",
                        ProductConditionType.NOT_APPLICABLE
                ),
                arguments(
                        "Клавиатура Magic Keyboard iPad Pro Black",
                        "ACCESSORY_IPAD",
                        ProductConditionType.NOT_APPLICABLE
                ),
                arguments(
                        "PlayStation 5 Dualsense Midnight Black",
                        "PODS_WATCH_OTHER_DEVICE",
                        ProductConditionType.NEW
                ),
                arguments(
                        "iPhone Air Magsafe Battery Pack",
                        "POWER_BANK",
                        ProductConditionType.NOT_APPLICABLE
                ),
                arguments(
                        "Наконечники Elago Metal Tips для Apple Pencil 1/2/Pro/USB-C (2шт.)",
                        "ACCESSORY_IPAD",
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
                ),
                arguments(
                        "Подзарядка устройства",
                        "SETUP_SERVICE",
                        ProductConditionType.NOT_APPLICABLE
                )
        );
    }

    private static Stream<Arguments> septemberMobiSphereCases() {
        return Stream.of(
                arguments(
                        "USB-C - Lightning No Box",
                        "CHARGER_CABLE",
                        ProductConditionType.NOT_APPLICABLE
                ),
                arguments(
                        "Картхолдер VLP из экокожи с MagSafe Черный",
                        "OTHER_ACCESSORY_PRODUCT",
                        ProductConditionType.NOT_APPLICABLE
                )
        );
    }

    private static Stream<Arguments> approvedChargingCases() {
        return Stream.of(
                arguments("Apple Power Adapter 30W Original", "CHARGER_CABLE",
                        ProductConditionType.NOT_APPLICABLE),
                arguments("No Box Apple Cable USB-C to USB-C 60W", "CHARGER_CABLE",
                        ProductConditionType.NOT_APPLICABLE),
                arguments("Samsung Power Adapter 25W Original", "CHARGER_CABLE",
                        ProductConditionType.NOT_APPLICABLE),
                arguments("Блок Baseus 30w Speed Mini Белый", "CHARGER_CABLE",
                        ProductConditionType.NOT_APPLICABLE),
                arguments("Блок Baseus 30w Speed Mini Черный", "CHARGER_CABLE",
                        ProductConditionType.NOT_APPLICABLE),
                arguments("Блок Baseus 45w EnerCore CJ11 Черный", "CHARGER_CABLE",
                        ProductConditionType.NOT_APPLICABLE),
                arguments("Блок Baseus 65W Fast Charger", "CHARGER_CABLE",
                        ProductConditionType.NOT_APPLICABLE),
                arguments("Блок Baseus GAN 67W Fast Charger", "CHARGER_CABLE",
                        ProductConditionType.NOT_APPLICABLE),
                arguments("Блок Baseus Type-c 20W Speed Mini белый", "CHARGER_CABLE",
                        ProductConditionType.NOT_APPLICABLE),
                arguments("Блок Baseus Type-c 20W Speed Mini Черный", "CHARGER_CABLE",
                        ProductConditionType.NOT_APPLICABLE),
                arguments("Комплект Baseus 20w  Белый Lightning", "CHARGER_CABLE",
                        ProductConditionType.NOT_APPLICABLE),
                arguments("Комплект Baseus 20w  Белый Type-c", "CHARGER_CABLE",
                        ProductConditionType.NOT_APPLICABLE),
                arguments("Комплект Baseus 20w  Черный Lightning", "CHARGER_CABLE",
                        ProductConditionType.NOT_APPLICABLE),
                arguments("Комплект Baseus 20w  Черный Type-c", "CHARGER_CABLE",
                        ProductConditionType.NOT_APPLICABLE),
                arguments("Комплект Baseus Gan5 30w Type-c (с кабелем) White", "CHARGER_CABLE",
                        ProductConditionType.NOT_APPLICABLE)
        );
    }

    private static Stream<Arguments> powerBankCases() {
        return Stream.of(
                arguments("Powerbank HOCO Q 34 10K MAH", "POWER_BANK",
                        ProductConditionType.NOT_APPLICABLE),
                arguments("Повербанк Magsafe Hoco 10k Mah J117A", "POWER_BANK",
                        ProductConditionType.NOT_APPLICABLE),
                arguments("Внешний аккумулятор VLP Solid Energy 5000mAh Qi2 20w белый",
                        "POWER_BANK", ProductConditionType.NOT_APPLICABLE),
                arguments("Портативный аккумулятор Keephone Magcube MagSafe 10K MAH",
                        "POWER_BANK", ProductConditionType.NOT_APPLICABLE),
                arguments("iPhone Air Magsafe Battery Pack", "POWER_BANK",
                        ProductConditionType.NOT_APPLICABLE)
        );
    }

    private static Stream<Arguments> adapterCases() {
        return Stream.of(
                arguments("Адаптер VLP Infinity USB-C Hub 5 в 1 Графит",
                        "OTHER_ACCESSORY_PRODUCT", ProductConditionType.NOT_APPLICABLE),
                arguments("Переходник Baseus UltraJoy 7-Port HUB",
                        "OTHER_ACCESSORY_PRODUCT", ProductConditionType.NOT_APPLICABLE),
                arguments("Евро-переходник",
                        "OTHER_ACCESSORY_PRODUCT", ProductConditionType.NOT_APPLICABLE),
                arguments("Переходник Keephone UNIVERSAL TRAVEL",
                        "OTHER_ACCESSORY_PRODUCT", ProductConditionType.NOT_APPLICABLE),
                arguments("Сетевой переходник Merkan",
                        "OTHER_ACCESSORY_PRODUCT", ProductConditionType.NOT_APPLICABLE),
                arguments("Lightning 3.5 AUX AUDIO",
                        "OTHER_ACCESSORY_PRODUCT", ProductConditionType.NOT_APPLICABLE),
                arguments("Кабель Lightning 3.5 AUX AUDIO",
                        "OTHER_ACCESSORY_PRODUCT", ProductConditionType.NOT_APPLICABLE),
                arguments("Переходник СЗУ на Type-c 20W PD POWER ADAPTER ORIG MODEL A2347",
                        "CHARGER_CABLE", ProductConditionType.NOT_APPLICABLE)
        );
    }

    private static Stream<Arguments> confirmedAmbiguousChargingCases() {
        return Stream.of(
                arguments("CЗУ Ugreen X512 Type-C 20W белый", "CHARGER_CABLE",
                        ProductConditionType.NOT_APPLICABLE),
                arguments("CЗУ Ugreen X512 Type-C 20W черный", "CHARGER_CABLE",
                        ProductConditionType.NOT_APPLICABLE),
                arguments("CЗУ Ugreen X513 Type-C 30W  белый", "CHARGER_CABLE",
                        ProductConditionType.NOT_APPLICABLE),
                arguments("USB-C - Lightning No Box", "CHARGER_CABLE",
                        ProductConditionType.NOT_APPLICABLE),
                arguments("Беспроводное зар. устройство VLP Lite Power Snap Qi2 Apple Watch",
                        "CHARGER_CABLE", ProductConditionType.NOT_APPLICABLE),
                arguments("Станция 3 в 1 (Стоячая)", "CHARGER_CABLE",
                        ProductConditionType.NOT_APPLICABLE),
                arguments("Taggy Keephone белый", "OTHER_ACCESSORY_PRODUCT",
                        ProductConditionType.NOT_APPLICABLE),
                arguments("Taggy Keephone черный", "OTHER_ACCESSORY_PRODUCT",
                        ProductConditionType.NOT_APPLICABLE)
        );
    }

    private static Stream<Arguments> legacyUnmappedRegressionCases() {
        return Stream.of(
                arguments(
                        "Phone 15 Pro Max 256GB Natural Titanium (A) 87% Б/У (H642N5Q7TP)",
                        "IPHONE_USED",
                        ProductConditionType.USED
                ),
                arguments(
                        "iPhone 17 Pro 256GB Cosmic Orange E-SIM (A) 100% \u0411/\u0423 (H65VWKNYW2)",
                        "IPHONE_USED",
                        ProductConditionType.USED
                ),
                arguments(
                        "iPhone 17 Pro Max 256GB Silver (A) 100% \u0411/\u0423 (G7R3707P9T)",
                        "IPHONE_USED",
                        ProductConditionType.USED
                ),
                arguments(
                        "iPhone 14 Pro Max 256Gb Deep Purple (B) 100% (RXC16KYWQH) \u0411/\u0423",
                        "IPHONE_USED",
                        ProductConditionType.USED
                )
        );
    }

    private static Stream<Arguments> customerMethodologyCases() {
        return Stream.of(
                arguments(
                        "Подписка PlayStation Plus",
                        "SETUP_SERVICE",
                        ProductConditionType.NOT_APPLICABLE
                ),
                arguments(
                        "Платный ремонт iPhone",
                        "SETUP_SERVICE",
                        ProductConditionType.NOT_APPLICABLE
                ),
                arguments(
                        "Блок питания Apple USB-C 20W",
                        "CHARGER_CABLE",
                        ProductConditionType.NOT_APPLICABLE
                ),
                arguments(
                        "Провод USB-C to Lightning",
                        "CHARGER_CABLE",
                        ProductConditionType.NOT_APPLICABLE
                ),
                arguments(
                        "Пауэрбанк MagSafe 10000 mAh",
                        "POWER_BANK",
                        ProductConditionType.NOT_APPLICABLE
                ),
                arguments(
                        "Портативный аккумулятор Baseus 20000 mAh",
                        "POWER_BANK",
                        ProductConditionType.NOT_APPLICABLE
                ),
                arguments(
                        "Монопод для смартфона",
                        "OTHER_ACCESSORY_PRODUCT",
                        ProductConditionType.NOT_APPLICABLE
                ),
                arguments(
                        "Комплект защитных стекол Samsung S25",
                        "GLASS_SAMSUNG",
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
