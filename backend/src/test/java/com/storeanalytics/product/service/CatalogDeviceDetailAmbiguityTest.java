package com.storeanalytics.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.product.model.ProductSourceKind;
import com.storeanalytics.product.service.CatalogDeviceDetailProposer.Observation;
import com.storeanalytics.product.service.CatalogDeviceDetailProposer.Reason;
import com.storeanalytics.product.service.CatalogDeviceDetailProposer.Status;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CatalogDeviceDetailAmbiguityTest {

    private final CatalogDeviceDetailProposer proposer = new CatalogDeviceDetailProposer();

    @ParameterizedTest
    @ValueSource(strings = {
        "Беспроводное зар. устройство Example Apple Watch",
        "Зар устройство Example Apple Watch",
        "БЗУ Example Apple Watch New"
    })
    void abbreviatedChargerIsNotAWatch(String name) {
        var result = proposer.propose(new Observation(name, ProductSourceKind.PRODUCT, ""));
        assertThat(result.status()).isEqualTo(Status.OUT_OF_SCOPE);
        assertThat(result.candidateCategoryCode()).isNull();
        assertThat(result.reasons()).containsExactly(Reason.ACCESSORY_OR_COMPONENT);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Apple Watch Ultra 2 49mm Black Ocean Band New",
        "Apple Watch Series 9 45mm Sport Band New"
    })
    void fullWatchModelWithIncludedBandIsAReviewableDeviceCandidate(String name) {
        var result = proposer.propose(new Observation(name, ProductSourceKind.PRODUCT, ""));
        assertThat(result.candidateCategoryCode()).isEqualTo("WATCH_APPLE");
        assertThat(result.status()).isEqualTo(Status.NEEDS_REVIEW);
        assertThat(result.reasons()).contains(Reason.INCLUDED_BAND_DESCRIPTION);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Band for Apple Watch Ultra 2 49mm New",
        "Apple Watch Ultra 2 49mm Case New",
        "Apple Watch Sport Band New"
    })
    void modelNameDoesNotOverrideAnExplicitAccessory(String name) {
        var result = proposer.propose(new Observation(name, ProductSourceKind.PRODUCT, ""));
        assertThat(result.status()).isEqualTo(Status.OUT_OF_SCOPE);
        assertThat(result.candidateCategoryCode()).isNull();
    }
}
