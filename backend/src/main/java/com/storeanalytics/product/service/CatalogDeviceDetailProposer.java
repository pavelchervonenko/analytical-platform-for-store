package com.storeanalytics.product.service;

import com.storeanalytics.product.model.ProductConditionType;
import com.storeanalytics.product.model.ProductSourceKind;
import com.storeanalytics.product.service.CatalogDeviceCategoryPolicy.DeviceType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only, explainable proposals for tablet/laptop/watch details.
 * Deliberately not wired into sale sync: proposed codes require a reviewed rollout.
 * All results, including PROPOSED, still require an authorized confirmation.
 */
public final class CatalogDeviceDetailProposer {

    public static final String POLICY_VERSION = "catalog-device-details-v2";

    public enum Status {
        PROPOSED,
        NEEDS_REVIEW,
        CONFLICT,
        OUT_OF_SCOPE
    }

    public enum Reason {
        EMPTY_NAME,
        SERVICE_SOURCE,
        ACCESSORY_OR_COMPONENT,
        SERVICE_NAME,
        SPECIALIZED_FITNESS_CATEGORY,
        DEVICE_TYPE_UNRESOLVED,
        MULTIPLE_DEVICE_TYPES,
        BRAND_UNRESOLVED,
        MULTIPLE_BRANDS,
        CONDITION_UNRESOLVED,
        CONDITION_CONFLICT,
        ASIS_NOT_SUPPORTED_FOR_THIS_TYPE,
        SOURCE_GROUP_CONFLICT,
        SOURCE_KIND_UNRESOLVED,
        DEVICE_STATUS_ANNOTATION,
        INCLUDED_BAND_DESCRIPTION,
        CONFIRMATION_REQUIRED
    }

    public record Observation(String name, ProductSourceKind sourceKind, String sourceGroup) {
        public Observation {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(sourceKind, "sourceKind");
            sourceGroup = sourceGroup == null ? "" : sourceGroup;
        }
    }

    public record Evidence(String field, String value, String source) {
    }

    public record Proposal(
            String policyVersion,
            String observationFingerprint,
            Status status,
            Set<DeviceType> deviceTypes,
            Set<String> brandCandidates,
            ProductConditionType condition,
            String candidateCategoryCode,
            List<Reason> reasons,
            List<Evidence> evidence
    ) {
        public Proposal {
            deviceTypes = Set.copyOf(deviceTypes);
            brandCandidates = Set.copyOf(brandCandidates);
            reasons = List.copyOf(reasons);
            evidence = List.copyOf(evidence);
        }
    }

    public Proposal propose(Observation observation) {
        Objects.requireNonNull(observation, "observation");
        String name = normalize(observation.name());
        String group = normalize(observation.sourceGroup());
        String fingerprint = fingerprint(observation);
        if (name.isBlank()) {
            return outside(fingerprint, Reason.EMPTY_NAME);
        }
        if (observation.sourceKind() == ProductSourceKind.SERVICE) {
            return outside(fingerprint, Reason.SERVICE_SOURCE);
        }
        boolean reviewedGarminWatch = matches(name,
                "garmin\\s+(?:forerunner\\s+165\\s+music|v[ií]voactive\\s+6)");
        if (matches(name, "fitbit|whoop") || (matches(name, "garmin") && !reviewedGarminWatch)) {
            return outside(fingerprint, Reason.SPECIALIZED_FITNESS_CATEGORY);
        }
        boolean completeWatchWithBand = matches(name, "band")
                && name.matches("^(?:apple\\s+)?(?:watch|iwatch)\\s+"
                        + "(?:ultra(?:\\s+\\d+)?|series\\s+\\d+|se(?:\\s+\\d+)?|\\d+)\\b.*")
                && matches(name, "[0-9]{2}\\s*mm");
        completeWatchWithBand = completeWatchWithBand || (reviewedGarminWatch
                && name.matches("^garmin\\s+v[ií]voactive\\s+6\\b.*\\bwith\\s+black\\s+band\\b.*"));
        if (matches(name, "band") && !completeWatchWithBand) {
            return outside(fingerprint, Reason.ACCESSORY_OR_COMPONENT);
        }
        if (matches(name, "ч[еe]хол|чехлол|case|cover|ремеш\\p{L}*|strap|стекл\\p{L}*"
                + "|пленк\\p{L}*|film|pencil|стилус|клавиатур\\p{L}*|keyboard|мышь|mouse"
                + "|кабел\\p{L}*|cable|адаптер|adapter|заряд\\p{L}*|charger|зар[.]?\\s+устройство|сзу|азу|бзу|брелок"
                + "|держател\\p{L}*|подставк\\p{L}*|чехл\\p{L}*|дисплей|аккумулятор"
                + "|батарея|корпус|шлейф|запчаст\\p{L}*|s pen|stylus|protector")) {
            return outside(fingerprint, Reason.ACCESSORY_OR_COMPONENT);
        }
        if (matches(name, "настройк\\p{L}*|установк\\p{L}*|замена|диагностик\\p{L}*"
                + "|чистк\\p{L}*|гравировк\\p{L}*|перенос|активация")
                || name.matches("^(ремонт|repair|гарантия|warranty)(?:\\s|$).*")
                || name.contains("подписк")) {
            return outside(fingerprint, Reason.SERVICE_NAME);
        }

        Set<DeviceType> types = EnumSet.noneOf(DeviceType.class);
        Set<String> brands = new LinkedHashSet<>();
        List<Evidence> evidence = new ArrayList<>();
        List<Reason> reasons = new ArrayList<>();
        if (completeWatchWithBand) {
            reasons.add(Reason.INCLUDED_BAND_DESCRIPTION);
        }
        if (matches(name, "ipad|айпад|планшет|tablet|galaxy tab|xiaomi pad|redmi pad")) {
            types.add(DeviceType.TABLET);
        }
        if (matches(name, "macbook|макбук|ноутбук|laptop|notebook|thinkpad|ideapad|vivobook"
                + "|zenbook|matebook|magicbook|galaxy book")) {
            types.add(DeviceType.LAPTOP);
        }
        if (reviewedGarminWatch || matches(name, "watch|iwatch|часы")) {
            types.add(DeviceType.WATCH);
        }
        if (matches(name, "apple|ipad|айпад|macbook|макбук|iwatch")) {
            brands.add("APPLE");
        }
        if (matches(name, "samsung|самсунг|galaxy tab|galaxy watch|galaxy book")) {
            brands.add("SAMSUNG");
        }
        for (String brand : List.of("lenovo", "asus", "acer", "dell", "hp", "huawei",
                "honor", "xiaomi", "redmi", "amazfit", "realme", "oppo", "msi", "garmin")) {
            if (matches(name, brand)) {
                brands.add(brand.toUpperCase(Locale.ROOT));
            }
        }
        // Redmi is a product brand in the Xiaomi family, not a second conflicting maker.
        if (brands.contains("XIAOMI")) {
            brands.remove("REDMI");
        }
        types.forEach(type -> evidence.add(new Evidence("deviceType", type.name(), "NAME")));
        brands.forEach(brand -> evidence.add(new Evidence("manufacturerBrand", brand, "NAME_OR_MODEL_ALIAS")));
        ProductConditionType condition = condition(name, reasons);
        if (types.isEmpty()) {
            reasons.add(Reason.DEVICE_TYPE_UNRESOLVED);
        } else if (types.size() > 1) {
            reasons.add(Reason.MULTIPLE_DEVICE_TYPES);
        }
        if (brands.isEmpty()) {
            reasons.add(Reason.BRAND_UNRESOLVED);
        } else if (brands.size() > 1) {
            reasons.add(Reason.MULTIPLE_BRANDS);
        }
        if (!types.isEmpty() && matches(group, "iphone|айфон")) {
            reasons.add(Reason.SOURCE_GROUP_CONFLICT);
            evidence.add(new Evidence("sourceGroupHint", "PHONE", "SOURCE_GROUP"));
        }
        if (observation.sourceKind() == ProductSourceKind.UNKNOWN) {
            reasons.add(Reason.SOURCE_KIND_UNRESOLVED);
        }
        if (matches(name, "ремонт|repair|брак|гаранти\\p{L}*")) {
            reasons.add(Reason.DEVICE_STATUS_ANNOTATION);
        }
        boolean conflict = reasons.contains(Reason.MULTIPLE_DEVICE_TYPES)
                || reasons.contains(Reason.MULTIPLE_BRANDS)
                || reasons.contains(Reason.CONDITION_CONFLICT)
                || reasons.contains(Reason.SOURCE_GROUP_CONFLICT)
                || reasons.contains(Reason.ASIS_NOT_SUPPORTED_FOR_THIS_TYPE);
        String category = types.size() == 1 && brands.size() == 1
                ? CatalogDeviceCategoryPolicy.category(types.iterator().next(), brands.iterator().next())
                        .orElse(null)
                : null;
        Status status = conflict ? Status.CONFLICT
                : !reasons.isEmpty() ? Status.NEEDS_REVIEW : Status.PROPOSED;
        reasons.add(Reason.CONFIRMATION_REQUIRED);
        evidence.add(new Evidence("condition", condition.name(), "NAME_EXPLICIT_ONLY"));
        return new Proposal(POLICY_VERSION, fingerprint, status, types, brands,
                condition, category, reasons, evidence);
    }

    private ProductConditionType condition(String name, List<Reason> reasons) {
        boolean used = name.matches(".*(?<![\\p{L}\\p{N}])(?:б\\s*[/ ]\\s*у|used)(?![\\p{L}\\p{N}]).*");
        boolean fresh = matches(name, "new|новый|новая|новое");
        boolean asis = matches(name, "asis|as is");
        if (asis) {
            reasons.add(Reason.ASIS_NOT_SUPPORTED_FOR_THIS_TYPE);
        }
        if (used && (fresh || asis)) {
            reasons.add(Reason.CONDITION_CONFLICT);
            return ProductConditionType.UNKNOWN;
        }
        if (used) {
            return ProductConditionType.USED;
        }
        if (asis) {
            return ProductConditionType.ASIS;
        }
        if (fresh) {
            return ProductConditionType.NEW;
        }
        reasons.add(Reason.CONDITION_UNRESOLVED);
        return ProductConditionType.UNKNOWN;
    }

    private Proposal outside(String fingerprint, Reason reason) {
        return new Proposal(POLICY_VERSION, fingerprint, Status.OUT_OF_SCOPE, Set.of(), Set.of(),
                ProductConditionType.UNKNOWN, null, List.of(reason), List.of());
    }

    private static boolean matches(String value, String alternatives) {
        return value.matches("(?s).*(?<![\\p{L}\\p{N}])(?:" + alternatives
                + ")(?![\\p{L}\\p{N}]).*");
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replace('ё', 'е').replaceAll("[\\p{Pd}_]", " ").replaceAll("\\s+", " ").strip();
    }

    private static String fingerprint(Observation observation) {
        // Length framing avoids collisions caused by separators inside source values.
        StringBuilder framed = new StringBuilder();
        for (String field : List.of(POLICY_VERSION, observation.sourceKind().name(),
                observation.name(), observation.sourceGroup())) {
            framed.append(field.length()).append(':').append(field);
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(framed.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
