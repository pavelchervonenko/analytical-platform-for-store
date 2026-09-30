package com.storeanalytics.product.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.storeanalytics.product.model.CatalogCompatibilityEvidence.Confirmation;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.Context;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.Coverage;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.IdentityType;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.State;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.Subject;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.Target;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CatalogCompatibilityEvidenceTest {
    private static final Subject SUBJECT = new Subject("synthetic", ProductSourceKind.PRODUCT,
            IdentityType.EXTERNAL_ID, "same-id");
    private static final Instant START = Instant.parse("2026-09-30T00:00:00Z");
    private static final String FINGERPRINT = fingerprint(SUBJECT, "Name", "Group");

    @Test
    void confirmationHasHalfOpenProspectiveInterval() {
        var confirmation = confirmation(START.plusSeconds(10));
        assertThat(confirmation.appliesTo(new Context(SUBJECT, FINGERPRINT, START))).isTrue();
        assertThat(confirmation.appliesTo(new Context(SUBJECT, FINGERPRINT, START.minusNanos(1)))).isFalse();
        assertThat(confirmation.appliesTo(new Context(SUBJECT, FINGERPRINT, START.plusSeconds(10)))).isFalse();
    }

    @Test
    void confirmationNeedsAuthorReasonRevisionAndCannotBackdate() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new Confirmation(SUBJECT, FINGERPRINT, 0, "actor", "reason", START, START, null));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new Confirmation(SUBJECT, FINGERPRINT, 1, " ", "reason", START, START, null));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new Confirmation(SUBJECT, FINGERPRINT, 1, "actor", "", START, START, null));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new Confirmation(SUBJECT, FINGERPRINT, 1, "actor", "reason", START, START.minusNanos(1), null));
        assertThatIllegalArgumentException().isThrownBy(() -> confirmation(START));
    }

    @Test
    void unknownAndObservedEvidenceCannotClaimConfirmedCoverage() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new CatalogCompatibilityEvidence(State.UNKNOWN, Coverage.UNDETERMINED,
                        Set.of(Target.APPLE_WATCH), null));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new CatalogCompatibilityEvidence(State.OBSERVED, Coverage.EXCLUSIVE, Set.of(Target.APPLE_WATCH), null));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new CatalogCompatibilityEvidence(State.CONFIRMED, Coverage.EXCLUSIVE,
                        Set.of(Target.APPLE_WATCH), null));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new CatalogCompatibilityEvidence(State.OBSERVED, Coverage.UNDETERMINED,
                        Set.of(Target.APPLE_WATCH), confirmation(null)));
    }

    @Test
    void exclusiveMultiDeviceAndUniversalAreDifferentContracts() {
        assertThatIllegalArgumentException().isThrownBy(() -> confirmed(Coverage.EXCLUSIVE,
                Set.of(Target.APPLE_WATCH, Target.IPHONE)));
        assertThatIllegalArgumentException().isThrownBy(() ->
                confirmed(Coverage.MULTI_DEVICE, Set.of(Target.APPLE_WATCH)));
        assertThatIllegalArgumentException().isThrownBy(() ->
                confirmed(Coverage.UNIVERSAL_PHONE, Set.of(Target.IPHONE)));
        assertThatIllegalArgumentException().isThrownBy(() ->
                confirmed(Coverage.EXCLUSIVE, Set.of(Target.PHONE_UNIVERSAL)));
        assertThat(confirmed(Coverage.UNIVERSAL_PHONE, Set.of(Target.PHONE_UNIVERSAL)).coverage())
                .isEqualTo(Coverage.UNIVERSAL_PHONE);
        assertThat(confirmed(Coverage.UNDETERMINED, Set.of(Target.APPLE_WATCH)).coverage())
                .isEqualTo(Coverage.UNDETERMINED);
    }

    @Test
    void semanticFingerprintBindsConnectionIdentityKindNameAndGroup() {
        var sameCode = new Subject("synthetic", ProductSourceKind.PRODUCT, IdentityType.CATALOG_CODE, "same-id");
        var service = new Subject("synthetic", ProductSourceKind.SERVICE, IdentityType.EXTERNAL_ID, "same-id");
        var foreign = new Subject("foreign", ProductSourceKind.PRODUCT, IdentityType.EXTERNAL_ID, "same-id");
        var otherId = new Subject("synthetic", ProductSourceKind.PRODUCT, IdentityType.EXTERNAL_ID, "other-id");
        assertThat(Set.of(FINGERPRINT, fingerprint(sameCode, "Name", "Group"),
                fingerprint(service, "Name", "Group"), fingerprint(foreign, "Name", "Group"),
                fingerprint(otherId, "Name", "Group"), fingerprint(SUBJECT, "Other name", "Group"),
                fingerprint(SUBJECT, "Name", "Other group"), fingerprint(SUBJECT, "Name", null),
                fingerprint(SUBJECT, "Name", ""))).hasSize(9);
        assertThat(fingerprint(SUBJECT, "a|1:b", "c")).isNotEqualTo(fingerprint(SUBJECT, "a", "b|1:c"));
        assertThat(fingerprint(SUBJECT, "Name", "Group")).isEqualTo(FINGERPRINT);
    }

    @Test
    void unknownIdentityAndInvalidHashAreRejected() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new Subject("synthetic", ProductSourceKind.UNKNOWN, IdentityType.EXTERNAL_ID, "same-id"));
        assertThatIllegalArgumentException().isThrownBy(() -> new Context(SUBJECT, "not-a-hash", START));
    }

    @Test
    void evidenceTargetsCannotBeChangedThroughAnInputCollection() {
        var targets = new HashSet<>(Set.of(Target.APPLE_WATCH));
        var evidence = CatalogCompatibilityEvidence.observed(targets);
        targets.add(Target.IPHONE);
        assertThat(evidence.targets()).containsExactly(Target.APPLE_WATCH);
    }

    private CatalogCompatibilityEvidence confirmed(Coverage coverage, Set<Target> targets) {
        return new CatalogCompatibilityEvidence(State.CONFIRMED, coverage, targets, confirmation(null));
    }

    private Confirmation confirmation(Instant validTo) {
        return new Confirmation(SUBJECT, FINGERPRINT, 1, "actor", "reason", START, START, validTo);
    }

    private static String fingerprint(Subject subject, String name, String group) {
        return CatalogCompatibilityEvidence.observationFingerprint(subject, name, group);
    }
}
