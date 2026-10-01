package com.storeanalytics.product.service;

import com.storeanalytics.product.model.CatalogCompatibilityEvidence.Coverage;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.Target;
import com.storeanalytics.product.service.CatalogCompatibilityRecords.Action;
import com.storeanalytics.product.service.CatalogCompatibilityRecords.Observation;
import com.storeanalytics.product.service.CatalogCompatibilityRecords.Origin;
import com.storeanalytics.product.service.CatalogCompatibilityRecords.Request;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Objects;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Verifies bytes and exact reviewed card; does not invent the original approval's author or date. */
public final class CatalogLegacyCompatibilityEvidence {
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).build();

    private CatalogLegacyCompatibilityEvidence() { }

    public record Verified(String connectionKey, String name, String groupPath, Request request) {
        public void requireMatching(Observation observation) {
            require(connectionKey.equals(observation.subject().connectionKey())
                    && name.equals(observation.name()) && Objects.equals(groupPath, observation.groupPath())
                    && request.evidenceKey().equals("PRODUCT:" + observation.code()),
                    "Legacy evidence does not match the exact current catalog card");
        }
    }

    public static Verified verify(byte[] artifact, String expectedSha256, String evidenceKey) {
        require(artifact != null && artifact.length > 0 && artifact.length <= 2_000_000, "Invalid evidence size");
        require(expectedSha256 != null && expectedSha256.matches("[a-f0-9]{64}"), "Invalid expected evidence hash");
        byte[] content = artifact.clone();
        require(hash(content).equals(expectedSha256), "Evidence content differs from expected hash");
        require(evidenceKey != null && evidenceKey.matches("PRODUCT:[A-Za-z0-9._-]{1,100}"), "Invalid evidence key");
        JsonNode root = JSON.readTree(content);
        require(root != null && root.isObject() && root.path("format_version").isInt()
                && root.path("format_version").intValue() == 1
                && "OWNER_CONFIRMED_NOT_APPLIED".equals(text(root, "mode")), "Unsupported evidence format");
        String connection = text(root, "connection_key");
        var decisions = root.path("decisions");
        require(decisions.isArray() && !decisions.isEmpty(), "Evidence has no decisions");
        var keys = new HashSet<String>();
        JsonNode selected = null;
        for (JsonNode row : decisions) {
            String key = text(row, "source_kind") + ":" + text(row, "code");
            require(keys.add(key), "Duplicate evidence identity");
            if (key.equals(evidenceKey)) {
                selected = row;
            }
        }
        require(selected != null, "Evidence key is absent");
        var compatibility = selected.path("confirmed_compatibility");
        require(compatibility.isArray() && !compatibility.isEmpty(), "No explicitly confirmed compatibility");
        var targets = new ArrayList<Target>();
        for (JsonNode target : compatibility) {
            require(target.isString(), "Invalid evidence target");
            targets.add(Target.valueOf(target.stringValue()));
        }
        Coverage coverage = targets.size() == 1 && targets.getFirst() == Target.PHONE_UNIVERSAL
                ? Coverage.UNIVERSAL_PHONE : Coverage.UNDETERMINED;
        String group = nullableGroup(selected);
        var request = new Request(Action.CONFIRM, coverage, targets, "Adoption of verified catalog owner evidence",
                Origin.LEGACY_ADOPTION, expectedSha256, evidenceKey);
        return new Verified(connection, text(selected, "expected_name"), group, request);
    }

    private static String nullableGroup(JsonNode row) {
        require(row.has("expected_group"), "Missing exact evidence group");
        JsonNode group = row.get("expected_group");
        require(group.isNull() || group.isString(), "Invalid evidence group");
        return group.isNull() ? null : group.stringValue();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        require(value.isString() && !value.stringValue().isBlank(), "Missing evidence field: " + field);
        return value.stringValue();
    }

    private static String hash(byte[] artifact) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(artifact));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }
}
