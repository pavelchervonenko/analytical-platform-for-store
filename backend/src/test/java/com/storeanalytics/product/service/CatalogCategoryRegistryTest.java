package com.storeanalytics.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.storeanalytics.product.model.AnalyticsCategoryKind;
import com.storeanalytics.product.model.ProductConditionType;
import org.junit.jupiter.api.Test;

class CatalogCategoryRegistryTest {

    private final CatalogCategoryRegistry registry = CatalogCategoryRegistry.standard();

    @Test
    void coversAgreedCodesWithoutTurningRegistryMembershipIntoRollout() {
        assertThat(registry.definitions()).hasSize(54);
        assertThat(registry.require("REPAIR_SERVICE").scope())
                .isEqualTo(CatalogCategoryRegistry.Scope.DEFERRED);
        assertThat(registry.require("DIAGNOSTICS_SERVICE").scope())
                .isEqualTo(CatalogCategoryRegistry.Scope.DEFERRED);
        assertThat(registry.require("IPAD_MAC").scope())
                .isEqualTo(CatalogCategoryRegistry.Scope.LEGACY);
        assertThat(registry.find("TABLET_APPLE")).isPresent();
        assertThat(registry.find("GLASS_OTHER")).isPresent();
        assertThat(registry.sha256()).matches("[a-f0-9]{64}");
    }

    @Test
    void packagingAndWatchesHaveDifferentFinancialRolesFromPhoneAccessories() {
        var packaging = registry.require("PACKAGING");
        assertThat(packaging.categoryKind()).isEqualTo(AnalyticsCategoryKind.OTHER);
        assertThat(packaging.countsAsAdditionalRevenue()).isFalse();
        assertThat(packaging.countsAsDevice()).isFalse();
        assertThat(packaging.countsAsPhone()).isFalse();
        var watch = registry.require("WATCH_SAMSUNG");
        assertThat(watch.countsAsDevice()).isTrue();
        assertThat(watch.countsAsPhone()).isFalse();
        assertThat(registry.require("CHARGER_CABLE").countsAsAdditionalRevenue()).isTrue();
    }

    @Test
    void glassOtherAcceptsScreenGlassAndNotPhoneOrCaseFunction() {
        assertThat(registry.require("GLASS_OTHER").confirmedFunctions())
                .containsExactly("SCREEN_GLASS");
        assertThat(registry.require("SAMSUNG_NEW").confirmedConditions())
                .containsExactly(ProductConditionType.NEW);
        assertThat(registry.require("IPHONE_NEW_ASIS").confirmedConditions())
                .containsExactlyInAnyOrder(ProductConditionType.NEW, ProductConditionType.ASIS);
    }

    @Test
    void doesNotGuessOtherBrandWhenBrandIsMissing() {
        assertThat(registry.deviceCategory("TABLET", null)).isEmpty();
        assertThat(registry.deviceCategory("LAPTOP", "UNKNOWN")).isEmpty();
        assertThat(registry.deviceCategory("WATCH", "other")).isEmpty();
        assertThat(registry.deviceCategory("TABLET", " Samsung ")).contains("TABLET_OTHER");
        assertThat(registry.deviceCategory("WATCH", "Samsung")).contains("WATCH_SAMSUNG");
        assertThat(registry.deviceCategory("LAPTOP", "Apple")).contains("LAPTOP_APPLE");
        assertThat(registry.proposalCategories("DEVICE")).containsEntry("IPAD", "TABLET_APPLE");
    }

    @Test
    void rejectsUnknownAutoDecisionWithoutChangingConditionOrAssigningIt() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ProductAutoClassificationDecision(
                "CHARGER_CABEL", ProductConditionType.NOT_APPLICABLE, "synthetic-rule"));
        assertThatIllegalArgumentException().isThrownBy(() -> registry.require(null));
        var result = new ProductAutoClassificationDecision(
                "IPHONE_NEW_ASIS", ProductConditionType.UNKNOWN, "synthetic-rule");
        assertThat(result.conditionType()).isEqualTo(ProductConditionType.UNKNOWN);
        assertThat(result.ruleId()).isEqualTo("synthetic-rule");
    }
}
