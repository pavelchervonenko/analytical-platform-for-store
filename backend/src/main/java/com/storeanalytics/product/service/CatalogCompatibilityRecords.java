package com.storeanalytics.product.service;

import com.storeanalytics.product.model.CatalogCompatibilityEvidence;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.Confirmation;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.Coverage;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.State;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.Subject;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.Target;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class CatalogCompatibilityRecords {
    private CatalogCompatibilityRecords() { }

    public enum Action { CONFIRM, REVOKE }
    public enum Origin { DIRECT_REVIEW, LEGACY_ADOPTION }

    public record Observation(UUID productId, UUID connectionId, Subject subject,
                              String code, String name, String groupPath) {
        public String fingerprint() {
            return CatalogCompatibilityEvidence.observationFingerprint(subject, name, groupPath);
        }
    }

    /** No client-selected actor, date, external identity or effective interval. */
    public record Request(Action action, Coverage coverage, List<Target> targets, String reason,
                          Origin origin, String evidenceSha256, String evidenceKey) {
        public Request {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(coverage, "coverage");
            Objects.requireNonNull(origin, "origin");
            targets = List.copyOf(targets);
            require(Set.copyOf(targets).size() == targets.size(), "duplicate targets");
            targets = targets.stream().sorted().toList();
            require(reason != null && !reason.isBlank() && reason.length() <= 1000, "reason is required");
            reason = reason.strip();
            if (origin == Origin.DIRECT_REVIEW) {
                require(evidenceSha256 == null && evidenceKey == null, "direct review has no adoption reference");
            } else {
                require(action == Action.CONFIRM, "adoption must confirm");
                require(coverage == Coverage.UNDETERMINED || coverage == Coverage.UNIVERSAL_PHONE,
                        "old target lists cannot prove exclusive or multi-device coverage");
                require(evidenceSha256 != null && evidenceSha256.matches("[a-f0-9]{64}"), "invalid evidence hash");
                require(evidenceKey != null && evidenceKey.matches("PRODUCT:[A-Za-z0-9._-]{1,100}"),
                        "invalid evidence key");
            }
            if (action == Action.REVOKE) {
                require(coverage == Coverage.UNDETERMINED && targets.isEmpty(), "revocation has no targets");
            }
        }

        public void validateFor(Observation observation, UUID actorId, Instant now) {
            if (origin == Origin.LEGACY_ADOPTION) {
                require(observation.code() != null && evidenceKey.equals("PRODUCT:" + observation.code()),
                        "legacy evidence code does not match the observed product");
            }
            if (action == Action.CONFIRM) {
                new CatalogCompatibilityEvidence(State.CONFIRMED, coverage, Set.copyOf(targets),
                        new Confirmation(observation.subject(), observation.fingerprint(), 1,
                                actorId.toString(), reason, now, now, null));
            }
        }
    }

    public record Decision(UUID id, UUID productId, Subject subject, String fingerprint, long revision,
                           Action action, Coverage coverage, Set<Target> targets, UUID actorId, String reason,
                           Origin origin, String evidenceSha256, String evidenceKey,
                           Instant recordedAt, Instant validTo) {
        public Decision {
            targets = Set.copyOf(targets);
        }

        public CatalogCompatibilityEvidence evidence() {
            require(action == Action.CONFIRM, "revocation cannot become confirmed evidence");
            return new CatalogCompatibilityEvidence(State.CONFIRMED, coverage, targets,
                    new Confirmation(subject, fingerprint, revision, actorId.toString(), reason,
                            recordedAt, recordedAt, validTo));
        }
    }

    public record Profile(Observation observation, Decision latest, String etag) { }

    private static void require(boolean value, String message) {
        if (!value) {
            throw new IllegalArgumentException(message);
        }
    }
}
