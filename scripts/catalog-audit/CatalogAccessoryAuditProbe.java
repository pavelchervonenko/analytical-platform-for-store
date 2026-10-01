package com.storeanalytics.product.service;

import com.storeanalytics.product.model.CatalogCompatibilityEvidence;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.Context;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.Coverage;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.State;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.IdentityType;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.Subject;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.Target;
import com.storeanalytics.product.model.ProductSourceKind;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.stream.Collectors;

/** Offline adapter. Old review targets never fabricate an authorized/dated product confirmation. */
public final class CatalogAccessoryAuditProbe {
    public static void main(String[] args) throws Exception {
        Instant evaluatedAt = Instant.parse(args[0]);
        var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        var policy = new CatalogAccessoryAttachPolicy();
        for (String line; (line = reader.readLine()) != null;) {
            String[] cells = line.split("\t", -1);
            if (cells.length != 9) {
                throw new IllegalArgumentException("Invalid accessory probe input");
            }
            var subject = new Subject(decode(cells[1]), ProductSourceKind.valueOf(cells[2]),
                    IdentityType.CATALOG_CODE, decode(cells[3]));
            String fingerprint = CatalogCompatibilityEvidence.observationFingerprint(subject,
                    decode(cells[4]), decode(cells[5]));
            Set<Target> targets = cells[7].isEmpty() ? Set.of() : Arrays.stream(cells[7].split(",", -1))
                    .map(Target::valueOf).collect(Collectors.toSet());
            if (!cells[7].isEmpty() && targets.size() != cells[7].split(",", -1).length) {
                throw new IllegalArgumentException("Duplicate compatibility target");
            }
            if (!Set.of("PROPOSAL", "NEEDS_REVIEW", "CONFLICT", "OWNER_CONFIRMED").contains(cells[8])) {
                throw new IllegalArgumentException("Unknown review status");
            }
            var evidence = cells[8].equals("CONFLICT")
                    ? new CatalogCompatibilityEvidence(State.CONFLICT, Coverage.UNDETERMINED, targets, null)
                    : targets.isEmpty() ? CatalogCompatibilityEvidence.unknown()
                    : CatalogCompatibilityEvidence.observed(targets);
            var decision = policy.evaluate(cells[6], evidence, new Context(subject, fingerprint, evaluatedAt));
            System.out.println(String.join("\t", cells[0], CatalogAccessoryAttachPolicy.VERSION,
                    decision.monetaryCategory(), decision.outcome().name(),
                    decision.role() == null ? "" : decision.role().name(), decision.reason().name(),
                    decision.summaryMetric().orElse(""), fingerprint));
        }
    }

    private static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
