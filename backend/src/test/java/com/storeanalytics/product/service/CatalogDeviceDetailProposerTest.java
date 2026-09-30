package com.storeanalytics.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.product.model.ProductConditionType;
import com.storeanalytics.product.model.ProductSourceKind;
import com.storeanalytics.product.service.CatalogDeviceCategoryPolicy.DeviceType;
import com.storeanalytics.product.service.CatalogDeviceDetailProposer.Observation;
import com.storeanalytics.product.service.CatalogDeviceDetailProposer.Reason;
import com.storeanalytics.product.service.CatalogDeviceDetailProposer.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CatalogDeviceDetailProposerTest {

    private final CatalogDeviceDetailProposer proposer = new CatalogDeviceDetailProposer();

    @ParameterizedTest
    @CsvSource({
        "Apple iPad Air 128GB New,TABLET_APPLE,APPLE,TABLET",
        "Samsung Galaxy Tab S10 New,TABLET_OTHER,SAMSUNG,TABLET",
        "Планшет Lenovo Tab новый,TABLET_OTHER,LENOVO,TABLET",
        "Xiaomi Redmi Pad New,TABLET_OTHER,XIAOMI,TABLET",
        "Apple MacBook Air New,LAPTOP_APPLE,APPLE,LAPTOP",
        "ASUS Zenbook New,LAPTOP_OTHER,ASUS,LAPTOP",
        "Ноутбук Lenovo ThinkPad New,LAPTOP_OTHER,LENOVO,LAPTOP",
        "Huawei MateBook New,LAPTOP_OTHER,HUAWEI,LAPTOP",
        "Samsung Galaxy Book New,LAPTOP_OTHER,SAMSUNG,LAPTOP",
        "Apple Watch Series 10 New,WATCH_APPLE,APPLE,WATCH",
        "iWatch SE New,WATCH_APPLE,APPLE,WATCH",
        "Samsung Galaxy Watch 8 New,WATCH_SAMSUNG,SAMSUNG,WATCH",
        "Часы Amazfit Balance New,WATCH_OTHER,AMAZFIT,WATCH"
    })
    void proposesApprovedLeavesFromIndependentTypeAndBrand(
            String name, String category, String brand, DeviceType type
    ) {
        var proposal = proposer.propose(new Observation(name, ProductSourceKind.PRODUCT, ""));
        assertThat(proposal.candidateCategoryCode()).isEqualTo(category);
        assertThat(proposal.deviceTypes()).containsExactly(type);
        assertThat(proposal.brandCandidates()).containsExactly(brand);
        assertThat(proposal.status()).isEqualTo(Status.PROPOSED);
        assertThat(proposal.reasons()).containsExactly(Reason.CONFIRMATION_REQUIRED);
        assertThat(proposal.evidence()).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Чехол Keephone для iPad New", "Чeхол Magnetic Apple Watch", "Ремешоу Watch Nike",
        "Стекло Samsung Galaxy Tab", "Magic Keyboard iPad", "Apple Pencil для iPad",
        "Зарядка Apple Watch", "Cover MacBook", "iPad display adapter", "Samsung S Pen Galaxy Tab",
        "Дисплей для MacBook", "Корпус Apple Watch", "Кабель MacBook", "Apple Watch Sport Band"
    })
    void doesNotTreatAnAccessoryTargetAsTheSoldDevice(String name) {
        var proposal = proposer.propose(new Observation(name, ProductSourceKind.PRODUCT, ""));
        assertThat(proposal.status()).isEqualTo(Status.OUT_OF_SCOPE);
        assertThat(proposal.candidateCategoryCode()).isNull();
        assertThat(proposal.brandCandidates()).isEmpty();
        assertThat(proposal.reasons()).containsExactly(Reason.ACCESSORY_OR_COMPONENT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Garmin Forerunner New", "Garmin Watch New", "Whoop 5 New", "Fitbit Air"})
    void preservesApprovedFitnessCategoryScope(String name) {
        var proposal = proposer.propose(new Observation(name, ProductSourceKind.PRODUCT, ""));
        assertThat(proposal.candidateCategoryCode()).isNull();
        assertThat(proposal.reasons()).containsExactly(Reason.SPECIALIZED_FITNESS_CATEGORY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Ремонт MacBook", "Настройка Apple Watch", "Установка программ iPad"})
    void servicesAreNotDevicesEvenInTheProductExport(String name) {
        assertThat(proposer.propose(new Observation(name, ProductSourceKind.PRODUCT, ""))
                .candidateCategoryCode()).isNull();
    }

    @Test
    void explicitServiceSourceWinsOverModelName() {
        var result = proposer.propose(new Observation("iPad New", ProductSourceKind.SERVICE, ""));
        assertThat(result.reasons()).containsExactly(Reason.SERVICE_SOURCE);
        assertThat(result.candidateCategoryCode()).isNull();
    }

    @Test
    void unknownBrandIsNotOtherBrand() {
        var result = proposer.propose(new Observation("Планшет New", ProductSourceKind.PRODUCT, ""));
        assertThat(result.status()).isEqualTo(Status.NEEDS_REVIEW);
        assertThat(result.candidateCategoryCode()).isNull();
        assertThat(result.reasons()).contains(Reason.BRAND_UNRESOLVED);
        assertThat(CatalogDeviceCategoryPolicy.category(DeviceType.TABLET, "UNKNOWN")).isEmpty();
        assertThat(CatalogDeviceCategoryPolicy.category(DeviceType.LAPTOP, "OTHER")).isEmpty();
        assertThat(CatalogDeviceCategoryPolicy.category(DeviceType.WATCH, null)).isEmpty();
    }

    @Test
    void samsungAloneDoesNotMeanPhoneTabletOrWatch() {
        var result = proposer.propose(new Observation("Samsung New", ProductSourceKind.PRODUCT, ""));
        assertThat(result.candidateCategoryCode()).isNull();
        assertThat(result.reasons()).contains(Reason.DEVICE_TYPE_UNRESOLVED);
    }

    @Test
    void ambiguousTypeOrBrandDoesNotUseFirstMatch() {
        var types = proposer.propose(new Observation("iPad MacBook New", ProductSourceKind.PRODUCT, ""));
        assertThat(types.status()).isEqualTo(Status.CONFLICT);
        assertThat(types.candidateCategoryCode()).isNull();
        assertThat(types.reasons()).contains(Reason.MULTIPLE_DEVICE_TYPES);
        var brands = proposer.propose(new Observation("Планшет Apple Samsung New", ProductSourceKind.PRODUCT, ""));
        assertThat(brands.status()).isEqualTo(Status.CONFLICT);
        assertThat(brands.candidateCategoryCode()).isNull();
        assertThat(brands.reasons()).contains(Reason.MULTIPLE_BRANDS);
    }

    @Test
    void missingConditionIsNotSilentlyNew() {
        var result = proposer.propose(new Observation("iPad Air", ProductSourceKind.PRODUCT, ""));
        assertThat(result.candidateCategoryCode()).isEqualTo("TABLET_APPLE");
        assertThat(result.condition()).isEqualTo(ProductConditionType.UNKNOWN);
        assertThat(result.status()).isEqualTo(Status.NEEDS_REVIEW);
        assertThat(result.reasons()).contains(Reason.CONDITION_UNRESOLVED);
    }

    @Test
    void contradictoryConditionAndAsisAreVisible() {
        var result = proposer.propose(new Observation("iPad New Б/У", ProductSourceKind.PRODUCT, ""));
        assertThat(result.status()).isEqualTo(Status.CONFLICT);
        assertThat(result.condition()).isEqualTo(ProductConditionType.UNKNOWN);
        var asis = proposer.propose(new Observation("MacBook ASIS", ProductSourceKind.PRODUCT, ""));
        assertThat(asis.status()).isEqualTo(Status.CONFLICT);
        assertThat(asis.reasons()).contains(Reason.ASIS_NOT_SUPPORTED_FOR_THIS_TYPE);
    }

    @Test
    void sourceGroupAndRepairAnnotationDoNotTurnLaptopIntoService() {
        var result = proposer.propose(new Observation("MacBook Б/У - ремонт", ProductSourceKind.PRODUCT,
                "/IPHONE (Б/У)"));
        assertThat(result.candidateCategoryCode()).isEqualTo("LAPTOP_APPLE");
        assertThat(result.condition()).isEqualTo(ProductConditionType.USED);
        assertThat(result.status()).isEqualTo(Status.CONFLICT);
        assertThat(result.reasons()).contains(Reason.SOURCE_GROUP_CONFLICT, Reason.DEVICE_STATUS_ANNOTATION);
    }

    @Test
    void fingerprintTracksInputAndResultCannotBeMutated() {
        var observation = new Observation("iPad New", ProductSourceKind.PRODUCT, "");
        var result = proposer.propose(observation);
        assertThat(result).isEqualTo(proposer.propose(observation));
        assertThat(result.observationFingerprint()).hasSize(64);
        assertThat(result.observationFingerprint()).isNotEqualTo(proposer.propose(
                new Observation("iPad New", ProductSourceKind.PRODUCT, "other")).observationFingerprint());
        assertThatThrownBy(() -> result.brandCandidates().add("SAMSUNG"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
