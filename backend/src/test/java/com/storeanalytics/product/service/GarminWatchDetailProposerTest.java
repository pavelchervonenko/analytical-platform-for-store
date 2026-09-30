package com.storeanalytics.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.product.model.ProductConditionType;
import com.storeanalytics.product.model.ProductSourceKind;
import com.storeanalytics.product.service.CatalogDeviceCategoryPolicy.DeviceType;
import com.storeanalytics.product.service.CatalogDeviceDetailProposer.Observation;
import com.storeanalytics.product.service.CatalogDeviceDetailProposer.Reason;
import com.storeanalytics.product.service.CatalogDeviceDetailProposer.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GarminWatchDetailProposerTest {
    private final CatalogDeviceDetailProposer proposer = new CatalogDeviceDetailProposer();

    @ParameterizedTest
    @ValueSource(strings = {
        "Garmin Forerunner 165 Music Whitestone",
        "Garmin Vivoactive 6 Slate with Black Band", "Garmin vívoactive 6"
    })
    void reviewedModelsAreOtherWatchesWithoutInventedCondition(String name) {
        var result = proposer.propose(new Observation(name, ProductSourceKind.PRODUCT, "/Фитнесс браслеты"));
        assertThat(result.candidateCategoryCode()).isEqualTo("WATCH_OTHER");
        assertThat(result.deviceTypes()).containsExactly(DeviceType.WATCH);
        assertThat(result.brandCandidates()).containsExactly("GARMIN");
        assertThat(result.condition()).isEqualTo(ProductConditionType.UNKNOWN);
        assertThat(result.status()).isEqualTo(Status.NEEDS_REVIEW);
        assertThat(result.reasons()).contains(Reason.CONDITION_UNRESOLVED, Reason.CONFIRMATION_REQUIRED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Ремешок для Garmin Vivoactive 6", "Garmin Vivoactive 6 replacement band",
        "Чехол Garmin Forerunner 165 Music", "Кабель Garmin Vivoactive 6",
        "Зарядка Garmin Forerunner 165 Music", "Стекло Garmin Vivoactive 6",
        "Garmin Vivoactive 6 Slate with Black Band charger"
    })
    void accessoryMentionsNeverBecomeWatches(String name) {
        var result = proposer.propose(new Observation(name, ProductSourceKind.PRODUCT, ""));
        assertThat(result.status()).isEqualTo(Status.OUT_OF_SCOPE);
        assertThat(result.candidateCategoryCode()).isNull();
        assertThat(result.reasons()).containsExactly(Reason.ACCESSORY_OR_COMPONENT);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Garmin", "Garmin Watch New", "Garmin Forerunner 165",
        "Garmin Forerunner 1650 Music", "Garmin Vivoactive 60",
        "Garmin Vivoactive 7", "Whoop 5.0", "Fitbit Air"
    })
    void doesNotBroadenApprovalToTheWholeBrandOrSimilarModelNumbers(String name) {
        var result = proposer.propose(new Observation(name, ProductSourceKind.PRODUCT, ""));
        assertThat(result.candidateCategoryCode()).isNull();
        assertThat(result.reasons()).containsExactly(Reason.SPECIALIZED_FITNESS_CATEGORY);
    }

    @Test
    void bandInCompleteVivoactiveDescriptionIsNotAStandaloneAccessory() {
        var result = proposer.propose(new Observation(
                "Garmin Vivoactive 6 Slate with Black Band New", ProductSourceKind.PRODUCT, ""));
        assertThat(result.candidateCategoryCode()).isEqualTo("WATCH_OTHER");
        assertThat(result.condition()).isEqualTo(ProductConditionType.NEW);
        assertThat(result.reasons()).contains(Reason.INCLUDED_BAND_DESCRIPTION);
        assertThat(result.reasons()).doesNotContain(Reason.ACCESSORY_OR_COMPONENT);
    }

    @Test
    void sourceKindAndServiceNameStillTakePrecedence() {
        assertThat(proposer.propose(new Observation(
                "Garmin Vivoactive 6", ProductSourceKind.SERVICE, "")).reasons())
                .containsExactly(Reason.SERVICE_SOURCE);
        assertThat(proposer.propose(new Observation(
                "Настройка Garmin Forerunner 165 Music", ProductSourceKind.PRODUCT, "")).reasons())
                .containsExactly(Reason.SERVICE_NAME);
    }

    @Test
    void conditionAndBrandConflictsStayVisible() {
        var state = proposer.propose(new Observation(
                "Garmin Vivoactive 6 New Б/У", ProductSourceKind.PRODUCT, ""));
        assertThat(state.status()).isEqualTo(Status.CONFLICT);
        assertThat(state.reasons()).contains(Reason.CONDITION_CONFLICT);
        var brands = proposer.propose(new Observation(
                "Garmin Vivoactive 6 Samsung Watch New", ProductSourceKind.PRODUCT, ""));
        assertThat(brands.status()).isEqualTo(Status.CONFLICT);
        assertThat(brands.candidateCategoryCode()).isNull();
        assertThat(brands.reasons()).contains(Reason.MULTIPLE_BRANDS);
    }
}
