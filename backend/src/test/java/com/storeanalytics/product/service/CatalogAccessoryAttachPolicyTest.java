package com.storeanalytics.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.storeanalytics.product.model.CatalogCompatibilityEvidence;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.Confirmation;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.Context;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.Coverage;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.IdentityType;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.State;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.Subject;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.Target;
import com.storeanalytics.product.model.ProductSourceKind;
import com.storeanalytics.product.service.CatalogAccessoryAttachPolicy.Outcome;
import com.storeanalytics.product.service.CatalogAccessoryAttachPolicy.Reason;
import com.storeanalytics.product.service.CatalogAccessoryAttachPolicy.Role;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class CatalogAccessoryAttachPolicyTest {
    private static final Instant START = Instant.parse("2026-09-30T00:00:00Z");
    private static final Subject SUBJECT = new Subject("synthetic", ProductSourceKind.PRODUCT,
            IdentityType.EXTERNAL_ID, "synthetic-id");
    private static final String FINGERPRINT = CatalogCompatibilityEvidence
            .observationFingerprint(SUBJECT, "Synthetic charger", "Synthetic group");
    private static final Context CONTEXT = new Context(SUBJECT, FINGERPRINT, START.plusSeconds(60));
    private final CatalogAccessoryAttachPolicy policy = new CatalogAccessoryAttachPolicy();

    @Test
    void watchOnlyChargerKeepsMoneyAndHasExactlyOneLeafWithoutSameReceiptRequirement() {
        var result = policy.evaluate("CHARGER_CABLE", confirmed(Coverage.EXCLUSIVE, Target.APPLE_WATCH), CONTEXT);
        assertThat(result.monetaryCategory()).isEqualTo("CHARGER_CABLE");
        assertThat(result.outcome()).isEqualTo(Outcome.ASSIGNED);
        assertThat(result.role()).isEqualTo(Role.ACCESSORY_APPLE_WATCH);
        assertThat(result.reason()).isEqualTo(Reason.WATCH_ONLY_CONFIRMED);
        assertThat(result.summaryMetric()).contains("ACCESSORY_PODS_WATCH");
        // Reuse a product confirmation for another sale; no receipt/device pairing is an input.
        assertThat(policy.evaluate("CHARGER_CABLE", confirmed(Coverage.EXCLUSIVE, Target.APPLE_WATCH),
                new Context(SUBJECT, FINGERPRINT, START.plusSeconds(120)))).isEqualTo(result);
    }

    @Test
    void confirmedSingletonWithoutCoverageDoesNotProveWatchExclusivity() {
        var result = policy.evaluate("CHARGER_CABLE", confirmed(Coverage.UNDETERMINED, Target.APPLE_WATCH), CONTEXT);
        assertThat(result.outcome()).isEqualTo(Outcome.REVIEW_PRODUCT);
        assertThat(result.reason()).isEqualTo(Reason.WATCH_EXCLUSIVITY_UNCONFIRMED);
        assertThat(result.role()).isNull();
    }

    @Test
    void observedWatchHintDoesNotBecomeAnOfficialRole() {
        var result = policy.evaluate("CHARGER_CABLE",
                CatalogCompatibilityEvidence.observed(Set.of(Target.APPLE_WATCH)), CONTEXT);
        assertThat(result.outcome()).isEqualTo(Outcome.REVIEW_PRODUCT);
        assertThat(result.role()).isNull();
    }

    @Test
    void threeInOneStationDefersToExistingRuleInsteadOfThreeContributions() {
        var result = policy.evaluate("CHARGER_CABLE",
                confirmed(Coverage.MULTI_DEVICE, Target.IPHONE, Target.AIRPODS, Target.APPLE_WATCH), CONTEXT);
        assertThat(result.outcome()).isEqualTo(Outcome.DEFER_TO_EXISTING);
        assertThat(result.reason()).isEqualTo(Reason.MULTI_DEVICE_CHARGER_UNCHANGED);
        assertThat(result.role()).isNull();
        assertThat(result.summaryMetric()).isEmpty();
    }

    @Test
    void unknownOrdinaryChargerAndVlpBundleAreNotSilentlyRemovedOrSplit() {
        var result = policy.evaluate("CHARGER_CABLE", CatalogCompatibilityEvidence.unknown(), CONTEXT);
        assertThat(result.outcome()).isEqualTo(Outcome.DEFER_TO_EXISTING);
        assertThat(result.reason()).isEqualTo(Reason.EXISTING_CHARGER_RULE_UNCHANGED);
        assertThat(result.role()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = Target.class, names = {"IPHONE", "SAMSUNG_PHONE", "OTHER_PHONE", "MACBOOK"})
    void confirmedNonWatchChargerDoesNotChangeExistingMethodology(Target target) {
        assertThat(policy.evaluate("CHARGER_CABLE", confirmed(Coverage.EXCLUSIVE, target), CONTEXT).outcome())
                .isEqualTo(Outcome.DEFER_TO_EXISTING);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCESSORY_AIRPODS", "ACCESSORY_APPLE_WATCH"})
    void directAccessoryCategoryHasOneChildRoleAndOnlyASummaryHint(String category) {
        var result = policy.evaluate(category, CatalogCompatibilityEvidence.unknown(), CONTEXT);
        assertThat(result.role().name()).isEqualTo(category);
        assertThat(result.monetaryCategory()).isEqualTo(category);
        assertThat(result.summaryMetric()).contains("ACCESSORY_PODS_WATCH");
    }

    @Test
    void conflictingDirectAccessoryIsReviewedNotAllocatedToBothChildren() {
        var evidence = confirmed(Coverage.MULTI_DEVICE, Target.AIRPODS, Target.APPLE_WATCH);
        for (String category : Set.of("ACCESSORY_AIRPODS", "ACCESSORY_APPLE_WATCH")) {
            var result = policy.evaluate(category, evidence, CONTEXT);
            assertThat(result.outcome()).isEqualTo(Outcome.REVIEW_PRODUCT);
            assertThat(result.reason()).isEqualTo(Reason.CATEGORY_TARGET_CONFLICT);
            assertThat(result.role()).isNull();
        }
    }

    @ParameterizedTest
    @EnumSource(value = Target.class, names = {"IPHONE", "SAMSUNG_PHONE", "OTHER_PHONE", "PHONE_GENERIC"})
    void confirmedPhoneFilmHasPhoneRoleButKeepsMonetaryCategory(Target target) {
        var result = policy.evaluate("PROTECTIVE_FILM", confirmed(Coverage.EXCLUSIVE, target), CONTEXT);
        assertThat(result.monetaryCategory()).isEqualTo("PROTECTIVE_FILM");
        assertThat(result.role()).isEqualTo(Role.FILM_PHONE);
        assertThat(result.summaryMetric()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = Target.class,
            names = {"IPAD", "OTHER_TABLET", "TABLET_GENERIC", "APPLE_WATCH", "OTHER_NON_PHONE_DEVICE"})
    void confirmedNonPhoneFilmDoesNotContributeToPhoneMetric(Target target) {
        var result = policy.evaluate("PROTECTIVE_FILM", confirmed(Coverage.EXCLUSIVE, target), CONTEXT);
        assertThat(result.outcome()).isEqualTo(Outcome.NO_CONTRIBUTION);
        assertThat(result.role()).isNull();
        assertThat(result.monetaryCategory()).isEqualTo("PROTECTIVE_FILM");
    }

    @Test
    void observedOrUnknownFilmNeedsSaleReviewAndIsNotAutoConfirmed() {
        for (var evidence : Set.of(CatalogCompatibilityEvidence.unknown(),
                CatalogCompatibilityEvidence.observed(Set.of(Target.IPHONE)),
                CatalogCompatibilityEvidence.observed(Set.of(Target.TABLET_GENERIC)))) {
            var result = policy.evaluate("PROTECTIVE_FILM", evidence, CONTEXT);
            assertThat(result.outcome()).isEqualTo(Outcome.REVIEW_SALE);
            assertThat(result.role()).isNull();
        }
    }

    @Test
    void filmForPhoneAndTabletNeedsSaleReviewWithoutGuessingItsUse() {
        var result = policy.evaluate("PROTECTIVE_FILM",
                confirmed(Coverage.MULTI_DEVICE, Target.IPHONE, Target.IPAD), CONTEXT);
        assertThat(result.outcome()).isEqualTo(Outcome.REVIEW_SALE);
        assertThat(result.reason()).isEqualTo(Reason.MIXED_FILM_TARGETS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OTHER_CASE", "CASE_UNIVERSAL", "GLASS_PHONE_UNRESOLVED"})
    void productEvidenceDoesNotSubstituteForSaleAttribution(String category) {
        var result = policy.evaluate(category, confirmed(Coverage.EXCLUSIVE, Target.IPHONE), CONTEXT);
        assertThat(result.outcome()).isEqualTo(Outcome.REVIEW_SALE);
        assertThat(result.role()).isNull();
        assertThat(result.monetaryCategory()).isEqualTo(category);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POWER_BANK", "ACCESSORY_PODS_WATCH", "IPAD_MAC", "FILM_PHONE",
        "ACCESSORY_IPAD", "SETUP_SERVICE", "REPAIR_SERVICE", "GLASS_OTHER", "PACKAGING", "UNMAPPED", "EXCLUDE"})
    void unchangedCategoriesRetainExistingProjectionIncludingLegacyAndExcluded(String category) {
        var result = policy.evaluate(category, CatalogCompatibilityEvidence.unknown(), CONTEXT);
        assertThat(result.outcome()).isEqualTo(Outcome.DEFER_TO_EXISTING);
        assertThat(result.reason()).isEqualTo(Reason.LEGACY_OR_OUT_OF_SCOPE);
        assertThat(result.role()).isNull();
    }

    @Test
    void staleOrForeignConfirmationCannotActivateWatchOverride() {
        var otherConnection = new Subject("another", ProductSourceKind.PRODUCT,
                IdentityType.EXTERNAL_ID, "synthetic-id");
        var catalogCode = new Subject("synthetic", ProductSourceKind.PRODUCT,
                IdentityType.CATALOG_CODE, "synthetic-id");
        for (Context context : Set.of(new Context(SUBJECT, "0".repeat(64), START),
                new Context(otherConnection, FINGERPRINT, START), new Context(catalogCode, FINGERPRINT, START),
                new Context(SUBJECT, FINGERPRINT, START.minusNanos(1)))) {
            var result = policy.evaluate("CHARGER_CABLE", confirmed(Coverage.EXCLUSIVE, Target.APPLE_WATCH), context);
            assertThat(result.outcome()).isEqualTo(Outcome.REVIEW_PRODUCT);
            assertThat(result.reason()).isEqualTo(Reason.CONFIRMATION_NOT_APPLICABLE);
            assertThat(result.role()).isNull();
        }
    }

    @Test
    void conflictIsNotSilentlyDowngradedToOrdinaryChargerFallback() {
        var evidence = new CatalogCompatibilityEvidence(State.CONFLICT, Coverage.UNDETERMINED,
                Set.of(Target.APPLE_WATCH, Target.IPHONE), null);
        var result = policy.evaluate("CHARGER_CABLE", evidence, CONTEXT);
        assertThat(result.outcome()).isEqualTo(Outcome.REVIEW_PRODUCT);
        assertThat(result.reason()).isEqualTo(Reason.COMPATIBILITY_CONFLICT);
    }

    @Test
    void unknownCategoryAndImpossibleDecisionFailClosed() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                policy.evaluate("CHARGER_CABEL", CatalogCompatibilityEvidence.unknown(), CONTEXT));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new CatalogAccessoryAttachPolicy.Decision("CHARGER_CABLE", Outcome.REVIEW_PRODUCT,
                        Role.ACCESSORY_APPLE_WATCH, Reason.WATCH_EXCLUSIVITY_UNCONFIRMED));
    }

    @Test
    void serviceCannotBeCountedAsAnAccessoryEvenWithMatchingText() {
        var service = new Subject("synthetic", ProductSourceKind.SERVICE, IdentityType.EXTERNAL_ID, "same-id");
        var result = policy.evaluate("ACCESSORY_APPLE_WATCH", CatalogCompatibilityEvidence.unknown(),
                new Context(service, FINGERPRINT, START));
        assertThat(result.outcome()).isEqualTo(Outcome.REVIEW_PRODUCT);
        assertThat(result.reason()).isEqualTo(Reason.SOURCE_KIND_CONFLICT);
        assertThat(result.role()).isNull();
    }

    @Test
    void otherDeviceIsNotProofOfNonPhoneFilm() {
        var result = policy.evaluate("PROTECTIVE_FILM", confirmed(Coverage.EXCLUSIVE, Target.OTHER_DEVICE), CONTEXT);
        assertThat(result.outcome()).isEqualTo(Outcome.REVIEW_SALE);
        assertThat(result.reason()).isEqualTo(Reason.DEVICE_TYPE_UNRESOLVED);
        assertThat(result.role()).isNull();
    }

    private CatalogCompatibilityEvidence confirmed(Coverage coverage, Target... targets) {
        var confirmation = new Confirmation(SUBJECT, FINGERPRINT, 1, "synthetic-actor", "synthetic-reason",
                START, START, null);
        return new CatalogCompatibilityEvidence(State.CONFIRMED, coverage, Set.of(targets), confirmation);
    }
}
