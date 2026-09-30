package com.storeanalytics.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.product.model.ProductConditionType;
import com.storeanalytics.product.model.ProductSourceKind;
import org.junit.jupiter.api.Test;

class HairStylerAutoClassificationTest {

    private final ProductAutoClassificationRuleEngine engine =
            new ProductAutoClassificationRuleEngine();

    @Test
    void classifiesApprovedStylerModelsAsDevices() {
        for (String name : new String[]{
                "Dyson HS08 LONG BLUE/COOPER",
                "Dyson hs08 Long Defuse Blue Cooper",
                "Стайлер Dyson HS08, красный бархат",
                "Стайлер Dyson Airwrap Co-Anda 2x"
        }) {
            var decision = engine.classify(name, ProductSourceKind.PRODUCT).orElseThrow();
            assertThat(decision.categoryCode()).isEqualTo("HAIR_STYLERS");
            assertThat(decision.conditionType()).isEqualTo(ProductConditionType.NEW);
        }
        assertThat(engine.classify("Dyson HS08 Б/У", ProductSourceKind.PRODUCT)
                .orElseThrow().conditionType()).isEqualTo(ProductConditionType.USED);
    }

    @Test
    void doesNotTreatEveryDysonProductAsStylerOrOtherDevice() {
        assertThat(engine.classify("Пылесос Dyson V15", ProductSourceKind.PRODUCT)).isEmpty();
        assertThat(engine.classify("Насадка Dyson Airwrap", ProductSourceKind.PRODUCT)).isEmpty();
        assertThat(engine.classify("Чехол Dyson Airwrap", ProductSourceKind.PRODUCT)
                .orElseThrow().categoryCode()).isEqualTo("OTHER_ACCESSORY_PRODUCT");
        assertThat(engine.classify("Диагностика Dyson HS08", ProductSourceKind.SERVICE)
                .orElseThrow().categoryCode()).isEqualTo("SETUP_SERVICE");
    }
}
