package com.storeanalytics.product.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.integration.connection.model.IntegrationConnection;
import com.storeanalytics.sync.model.SourceSystem;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProductSourceGroupObservationTest {

    private static final Instant TIME = Instant.parse("2026-09-20T10:00:00Z");
    private final IntegrationConnection connection = connection("synthetic-connection");
    private final SourceProductGroup group = group(connection, "group-a");

    @Test
    void oldConstructorDistinguishesPresentFromUnobservedGroup() {
        assertThat(details(null, TIME).sourceGroupObserved()).isFalse();
        assertThat(details(group, TIME).sourceGroupObserved()).isTrue();
    }

    @Test
    void missingGroupInNewDocumentDoesNotEraseStoredGroup() {
        Product product = product(group);
        assertThat(product.updateFromLiveSklad(details(null, TIME.plusSeconds(1)))).isTrue();
        assertThat(product.getSourceGroup()).isSameAs(group);
        assertThat(product.getSourceUpdatedAt()).isEqualTo(TIME.plusSeconds(1));
    }

    @Test
    void repeatedDocumentWithoutGroupIsIdempotent() {
        Product product = product(group);
        assertThat(product.updateFromLiveSklad(details(null, TIME))).isFalse();
        assertThat(product.getSourceGroup()).isSameAs(group);
    }

    @Test
    void missingGroupStillAllowsOtherObservedFieldsToChange() {
        Product product = product(group);
        assertThat(product.updateFromLiveSklad(new ProductDetails(null, "changed-code", "sku",
                "Updated product", ProductSourceKind.SERVICE, TIME.plusSeconds(1)))).isTrue();
        assertThat(product.getSourceGroup()).isSameAs(group);
        assertThat(product.getCode()).isEqualTo("changed-code");
        assertThat(product.getName()).isEqualTo("Updated product");
        assertThat(product.getSourceKind()).isEqualTo(ProductSourceKind.SERVICE);
    }

    @Test
    void explicitObservedGroupCanReplaceStoredGroup() {
        Product product = product(group);
        SourceProductGroup replacement = group(connection, "group-b");
        assertThat(product.updateFromLiveSklad(details(replacement, TIME.plusSeconds(1)))).isTrue();
        assertThat(product.getSourceGroup()).isSameAs(replacement);
    }

    @Test
    void onlyExplicitObservedAbsenceCanClearGroup() {
        Product product = product(group);
        ProductDetails explicitAbsence = new ProductDetails(null, "code", null, "Synthetic product",
                ProductSourceKind.PRODUCT, TIME, true);
        assertThat(product.updateFromLiveSklad(explicitAbsence)).isTrue();
        assertThat(product.getSourceGroup()).isNull();
        assertThat(product.updateFromLiveSklad(explicitAbsence)).isFalse();
    }

    @Test
    void staleObservationCannotReplaceOrClearGroup() {
        Product product = product(group);
        assertThat(product.updateFromLiveSklad(details(group(connection, "older"), TIME.minusSeconds(1))))
                .isFalse();
        assertThat(product.updateFromLiveSklad(new ProductDetails(null, "code", null, "Synthetic product",
                ProductSourceKind.PRODUCT, TIME.minusSeconds(1), true))).isFalse();
        assertThat(product.getSourceGroup()).isSameAs(group);
    }

    @Test
    void undatedObservationCannotClearDatedGroup() {
        Product product = product(group);
        assertThat(product.updateFromLiveSklad(new ProductDetails(null, "code", null, "Synthetic product",
                ProductSourceKind.PRODUCT, null, true))).isFalse();
        assertThat(product.getSourceGroup()).isSameAs(group);
    }

    @Test
    void unobservedGroupCannotCarryAValue() {
        assertThatThrownBy(() -> new ProductDetails(group, "code", null, "Synthetic",
                ProductSourceKind.PRODUCT, TIME, false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void groupFromAnotherConnectionIsRejected() {
        Product product = product(group);
        SourceProductGroup foreign = group(connection("other"), "foreign");
        assertThatThrownBy(() -> product.updateFromLiveSklad(details(foreign, TIME.plusSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(product.getSourceGroup()).isSameAs(group);
    }

    @Test
    void manualGroupCannotBeAttachedToLiveSkladProduct() {
        Product product = product(group);
        SourceProductGroup manual = SourceProductGroup.manual("manual", "/Manual", "Manual", null);
        assertThatThrownBy(() -> product.updateFromLiveSklad(details(manual, TIME.plusSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(product.getSourceGroup()).isSameAs(group);
    }

    @Test
    void newProductWithoutGroupRemainsUngrouped() {
        Product product = product(null);
        assertThat(product.getSourceGroup()).isNull();
        assertThat(product.updateFromLiveSklad(details(null, TIME))).isFalse();
    }

    @Test
    void claimingProvisionalIdentityPreservesPreviouslyKnownGroup() {
        Product product = Product.fromLiveSklad(connection, "code",
                new ProductDetails(group, "code", null, "Synthetic product", ProductSourceKind.UNKNOWN, null));
        assertThat(product.claimLiveSkladIdentity("provider-id", details(null, TIME))).isTrue();
        assertThat(product.getExternalId()).isEqualTo("provider-id");
        assertThat(product.getSourceKind()).isEqualTo(ProductSourceKind.PRODUCT);
        assertThat(product.getSourceGroup()).isSameAs(group);
    }

    private Product product(SourceProductGroup sourceGroup) {
        return Product.fromLiveSklad(connection, "provider-id", details(sourceGroup, TIME));
    }

    private ProductDetails details(SourceProductGroup sourceGroup, Instant observedAt) {
        return new ProductDetails(sourceGroup, "code", null, "Synthetic product",
                ProductSourceKind.PRODUCT, observedAt);
    }

    private static IntegrationConnection connection(String key) {
        return new IntegrationConnection(key, SourceSystem.LIVESKLAD, "Synthetic", null, null);
    }

    private static SourceProductGroup group(IntegrationConnection connection, String id) {
        return SourceProductGroup.fromLiveSklad(connection, id, "/Synthetic/" + id, id, null);
    }
}
