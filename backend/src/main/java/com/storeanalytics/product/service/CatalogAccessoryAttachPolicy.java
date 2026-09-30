package com.storeanalytics.product.service;

import com.storeanalytics.product.model.CatalogCompatibilityEvidence;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.Context;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.Coverage;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.State;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.Target;
import com.storeanalytics.product.model.ProductSourceKind;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pure target policy for agreed accessory changes. Used in opt-in shadow snapshots, not live SQL.
 * DEFER_TO_EXISTING is not zero: callers must preserve the applicable legacy projection.
 */
public final class CatalogAccessoryAttachPolicy {
    public static final String VERSION = "catalog-accessory-roles-v1";
    private static final Set<String> SUPPORTED = Set.of("CHARGER_CABLE", "ACCESSORY_AIRPODS",
            "ACCESSORY_APPLE_WATCH", "PROTECTIVE_FILM", "OTHER_CASE", "CASE_UNIVERSAL",
            "GLASS_PHONE_UNRESOLVED");

    public enum Outcome { ASSIGNED, NO_CONTRIBUTION, REVIEW_PRODUCT, REVIEW_SALE, DEFER_TO_EXISTING }

    /** At most one leaf role per unit; the summary is not a second independent contribution. */
    public enum Role { ACCESSORY_AIRPODS, ACCESSORY_APPLE_WATCH, FILM_PHONE }

    public enum Reason {
        DIRECT_ACCESSORY_CATEGORY, WATCH_ONLY_CONFIRMED, WATCH_EXCLUSIVITY_UNCONFIRMED,
        MULTI_DEVICE_CHARGER_UNCHANGED, EXISTING_CHARGER_RULE_UNCHANGED,
        COMPATIBILITY_CONFLICT, CONFIRMATION_NOT_APPLICABLE, CONFIRMATION_REVOKED,
        CATEGORY_TARGET_CONFLICT, SOURCE_KIND_CONFLICT,
        PHONE_FILM_CONFIRMED, NON_PHONE_FILM_CONFIRMED, MIXED_FILM_TARGETS, DEVICE_TYPE_UNRESOLVED,
        SALE_CONFIRMATION_REQUIRED, LEGACY_OR_OUT_OF_SCOPE
    }

    public record Decision(String monetaryCategory, Outcome outcome, Role role, Reason reason) {
        public Decision {
            CatalogCategoryRegistry.standard().require(monetaryCategory);
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(reason, "reason");
            if ((outcome == Outcome.ASSIGNED) != (role != null)) {
                throw new IllegalArgumentException("only an assigned decision has a leaf role");
            }
        }

        /** Grouping hint only. Consumers sum the two children once, not this hint as another fact. */
        public Optional<String> summaryMetric() {
            return role == Role.ACCESSORY_AIRPODS || role == Role.ACCESSORY_APPLE_WATCH
                    ? Optional.of("ACCESSORY_PODS_WATCH") : Optional.empty();
        }
    }

    public Decision evaluate(String category, CatalogCompatibilityEvidence evidence, Context context) {
        CatalogCategoryRegistry.standard().require(category);
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(context, "context");
        if (!SUPPORTED.contains(category)) {
            return decision(category, Outcome.DEFER_TO_EXISTING, Reason.LEGACY_OR_OUT_OF_SCOPE);
        }
        if (context.subject().sourceKind() != ProductSourceKind.PRODUCT) {
            return decision(category, Outcome.REVIEW_PRODUCT, Reason.SOURCE_KIND_CONFLICT);
        }
        if (evidence.state() == State.CONFLICT) {
            return decision(category, Outcome.REVIEW_PRODUCT, Reason.COMPATIBILITY_CONFLICT);
        }
        if (evidence.state() == State.CONFIRMED && !evidence.confirmation().appliesTo(context)) {
            return decision(category, Outcome.REVIEW_PRODUCT, Reason.CONFIRMATION_NOT_APPLICABLE);
        }
        return switch (category) {
            case "CHARGER_CABLE" -> charger(evidence);
            case "ACCESSORY_AIRPODS" -> directAccessory(category, evidence, Role.ACCESSORY_AIRPODS,
                    Set.of(Target.AIRPODS, Target.APPLE_HEADPHONES));
            case "ACCESSORY_APPLE_WATCH" -> directAccessory(category, evidence, Role.ACCESSORY_APPLE_WATCH,
                    Set.of(Target.APPLE_WATCH));
            case "PROTECTIVE_FILM" -> film(evidence);
            default -> decision(category, Outcome.REVIEW_SALE, Reason.SALE_CONFIRMATION_REQUIRED);
        };
    }

    private Decision charger(CatalogCompatibilityEvidence evidence) {
        String category = "CHARGER_CABLE";
        if (!evidence.targets().contains(Target.APPLE_WATCH)) {
            return decision(category, Outcome.DEFER_TO_EXISTING, Reason.EXISTING_CHARGER_RULE_UNCHANGED);
        }
        if (evidence.state() == State.CONFIRMED) {
            if (evidence.coverage() == Coverage.EXCLUSIVE) {
                return new Decision(category, Outcome.ASSIGNED, Role.ACCESSORY_APPLE_WATCH,
                        Reason.WATCH_ONLY_CONFIRMED);
            }
            if (evidence.coverage() == Coverage.MULTI_DEVICE) {
                return decision(category, Outcome.DEFER_TO_EXISTING, Reason.MULTI_DEVICE_CHARGER_UNCHANGED);
            }
        }
        return decision(category, Outcome.REVIEW_PRODUCT, Reason.WATCH_EXCLUSIVITY_UNCONFIRMED);
    }

    private Decision directAccessory(String category, CatalogCompatibilityEvidence evidence,
                                     Role role, Set<Target> compatibleTargets) {
        if (!compatibleTargets.containsAll(evidence.targets())) {
            return decision(category, Outcome.REVIEW_PRODUCT, Reason.CATEGORY_TARGET_CONFLICT);
        }
        // Category is already an input fact; this method never assigns it from a target/name.
        return new Decision(category, Outcome.ASSIGNED, role, Reason.DIRECT_ACCESSORY_CATEGORY);
    }

    private Decision film(CatalogCompatibilityEvidence evidence) {
        String category = "PROTECTIVE_FILM";
        if (evidence.state() != State.CONFIRMED) {
            return decision(category, Outcome.REVIEW_SALE, Reason.SALE_CONFIRMATION_REQUIRED);
        }
        if (evidence.targets().contains(Target.OTHER_DEVICE)) {
            return decision(category, Outcome.REVIEW_SALE, Reason.DEVICE_TYPE_UNRESOLVED);
        }
        boolean phone = evidence.targets().stream().anyMatch(Target::isPhone);
        boolean nonPhone = evidence.targets().stream().anyMatch(target -> !target.isPhone());
        if (phone && nonPhone) {
            return decision(category, Outcome.REVIEW_SALE, Reason.MIXED_FILM_TARGETS);
        }
        return phone ? new Decision(category, Outcome.ASSIGNED, Role.FILM_PHONE, Reason.PHONE_FILM_CONFIRMED)
                : decision(category, Outcome.NO_CONTRIBUTION, Reason.NON_PHONE_FILM_CONFIRMED);
    }

    private Decision decision(String category, Outcome outcome, Reason reason) {
        return new Decision(category, outcome, null, reason);
    }
}
