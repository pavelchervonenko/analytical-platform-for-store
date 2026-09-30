package com.storeanalytics.product.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/** Typed product evidence, not a category assignment or a sale-attribution decision. */
public record CatalogCompatibilityEvidence(
        State state,
        Coverage coverage,
        Set<Target> targets,
        Confirmation confirmation
) {
    public enum State { UNKNOWN, OBSERVED, CONFIRMED, CONFLICT }

    /** A singleton observed target is not proof of exclusive compatibility. */
    public enum Coverage { UNDETERMINED, EXCLUSIVE, MULTI_DEVICE, UNIVERSAL_PHONE }

    public enum IdentityType { EXTERNAL_ID, CATALOG_CODE }

    public enum Target {
        IPHONE, SAMSUNG_PHONE, OTHER_PHONE, PHONE_GENERIC, PHONE_UNIVERSAL,
        IPAD, OTHER_TABLET, TABLET_GENERIC, MACBOOK, OTHER_LAPTOP, LAPTOP_GENERIC,
        APPLE_WATCH, SAMSUNG_WATCH, OTHER_WATCH, AIRPODS,
        APPLE_HEADPHONES, SAMSUNG_HEADPHONES, OTHER_HEADPHONES,
        CONSOLE, SPEAKER, MICROPHONE, FITNESS, SMART_GLASSES, CAMERA, HAIR_STYLER,
        OTHER_DEVICE, OTHER_NON_PHONE_DEVICE;

        public boolean isPhone() {
            return this == IPHONE || this == SAMSUNG_PHONE || this == OTHER_PHONE
                    || this == PHONE_GENERIC || this == PHONE_UNIVERSAL;
        }
    }

    /** Store is deliberately absent: the catalog belongs to a connection, not one shop. */
    public record Subject(String connectionKey, ProductSourceKind sourceKind, IdentityType identityType, String id) {
        public Subject {
            requireText(connectionKey, "connectionKey");
            Objects.requireNonNull(sourceKind, "sourceKind");
            Objects.requireNonNull(identityType, "identityType");
            requireText(id, "id");
            require(sourceKind != ProductSourceKind.UNKNOWN, "source kind must be resolved");
        }
    }

    /** Caller supplies the fact date and its observation, never the current product's state for an old sale. */
    public record Context(Subject subject, String observationFingerprint, Instant occurredAt) {
        public Context {
            Objects.requireNonNull(subject, "subject");
            requireFingerprint(observationFingerprint);
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    /**
     * Prospective confirmation contract. Persistence, authorization and revision arbitration
     * belong to the future catalog command service, not to this value object.
     */
    public record Confirmation(
            Subject subject,
            String observationFingerprint,
            long revision,
            String actorReference,
            String reason,
            Instant confirmedAt,
            Instant validFrom,
            Instant validTo
    ) {
        public Confirmation {
            Objects.requireNonNull(subject, "subject");
            requireFingerprint(observationFingerprint);
            require(revision > 0, "revision must be positive");
            requireText(actorReference, "actorReference");
            requireText(reason, "reason");
            Objects.requireNonNull(confirmedAt, "confirmedAt");
            Objects.requireNonNull(validFrom, "validFrom");
            require(!validFrom.isBefore(confirmedAt), "historical correction requires a separate controlled operation");
            require(validTo == null || validTo.isAfter(validFrom), "validTo must be after validFrom");
        }

        public boolean appliesTo(Context context) {
            Objects.requireNonNull(context, "context");
            return subject.equals(context.subject())
                    && observationFingerprint.equals(context.observationFingerprint())
                    && !context.occurredAt().isBefore(validFrom)
                    && (validTo == null || context.occurredAt().isBefore(validTo));
        }
    }

    public CatalogCompatibilityEvidence {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(coverage, "coverage");
        targets = Set.copyOf(targets);
        require((state == State.CONFIRMED) == (confirmation != null),
                "only confirmed evidence has confirmation provenance");
        if (state != State.CONFIRMED) {
            require(coverage == Coverage.UNDETERMINED, "coverage must be explicitly confirmed");
        }
        if (state == State.UNKNOWN) {
            require(targets.isEmpty(), "unknown evidence cannot claim a target");
        } else if (state != State.CONFLICT) {
            require(!targets.isEmpty(), "observed or confirmed evidence needs a target");
        }
        if (coverage == Coverage.EXCLUSIVE) {
            require(targets.size() == 1 && !targets.contains(Target.PHONE_UNIVERSAL),
                    "exclusive compatibility needs exactly one target");
        } else if (coverage == Coverage.MULTI_DEVICE) {
            require(targets.size() >= 2 && !targets.contains(Target.PHONE_UNIVERSAL),
                    "multi-device compatibility needs at least two explicit targets");
        } else if (coverage == Coverage.UNIVERSAL_PHONE) {
            require(targets.equals(Set.of(Target.PHONE_UNIVERSAL)),
                    "universal-phone compatibility must be explicit");
        }
    }

    public static CatalogCompatibilityEvidence unknown() {
        return new CatalogCompatibilityEvidence(State.UNKNOWN, Coverage.UNDETERMINED, Set.of(), null);
    }

    public static CatalogCompatibilityEvidence observed(Set<Target> targets) {
        return new CatalogCompatibilityEvidence(State.OBSERVED, Coverage.UNDETERMINED, targets, null);
    }

    /** Stable semantic input: no price, stock, sync timestamp or classifier-code hash. */
    public static String observationFingerprint(Subject subject, String productName, String sourceGroup) {
        Objects.requireNonNull(subject, "subject");
        require(productName != null && !productName.isBlank(), "productName is required");
        StringBuilder input = new StringBuilder("catalog-compatibility-observation-v1");
        for (String value : new String[]{subject.connectionKey(), subject.sourceKind().name(),
                subject.identityType().name(), subject.id(), productName, sourceGroup}) {
            input.append('|').append(value == null ? -1 : value.length()).append(':');
            if (value != null) {
                input.append(value);
            }
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static void requireFingerprint(String value) {
        require(value != null && value.matches("[a-f0-9]{64}"), "invalid observation fingerprint");
    }

    private static void requireText(String value, String field) {
        require(value != null && !value.isBlank() && value.equals(value.strip()), "invalid " + field);
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }
}
