package com.storeanalytics.product.service;

import com.storeanalytics.product.model.AnalyticsCategoryKind;
import com.storeanalytics.product.model.DeviceFamily;
import com.storeanalytics.product.model.ProductConditionType;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Shared code/attribute contract. Not a database seeder, assignment or rollout switch.
 * Financial flags describe the agreed target; they must not replace live DB flags yet.
 */
public final class CatalogCategoryRegistry {

    public static final String VERSION = "catalog-category-registry-v1";
    public static final String RESOURCE = "/catalog/category-registry-v1.tsv";
    private static final String HEADER = "code\tname\tcategory_kind\tdevice_family"
            + "\tcounts_as_phone\tcounts_as_device\tcounts_as_additional_revenue"
            + "\tscope\tdevice_type\tdevice_brand_match\tproposal_keys"
            + "\tconfirmed_functions\tconfirmed_conditions";
    private static final Set<String> DEVICE_TYPES = Set.of("TABLET", "LAPTOP", "WATCH");
    private static final Set<String> PROPOSAL_DOMAINS = Set.of("DEVICE", "SERVICE", "ITEM");

    /** Scope is a taxonomy boundary, not evidence that a category is deployed. */
    public enum Scope {
        STANDARD,
        LEGACY,
        DEFERRED,
        TECHNICAL
    }

    public record Definition(
            String code,
            String name,
            AnalyticsCategoryKind categoryKind,
            DeviceFamily deviceFamily,
            boolean countsAsPhone,
            boolean countsAsDevice,
            boolean countsAsAdditionalRevenue,
            Scope scope,
            String deviceType,
            String deviceBrandMatch,
            Set<String> proposalKeys,
            Set<String> confirmedFunctions,
            Set<ProductConditionType> confirmedConditions
    ) {
        public Definition {
            proposalKeys = Set.copyOf(proposalKeys);
            confirmedFunctions = Set.copyOf(confirmedFunctions);
            confirmedConditions = Set.copyOf(confirmedConditions);
        }
    }

    private final Map<String, Definition> definitions;
    private final Map<String, String> deviceMappings;
    private final Map<String, String> proposalMappings;
    private final String sha256;

    private CatalogCategoryRegistry(Map<String, Definition> entries, String content) {
        definitions = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
        Map<String, String> devices = new LinkedHashMap<>();
        Map<String, String> proposals = new LinkedHashMap<>();
        for (Definition entry : definitions.values()) {
            if (!entry.deviceType().isEmpty()) {
                putUnique(devices, entry.deviceType() + ":" + entry.deviceBrandMatch(), entry.code());
            }
            for (String key : entry.proposalKeys()) {
                putUnique(proposals, key, entry.code());
            }
        }
        for (String type : DEVICE_TYPES) {
            for (String brand : List.of("APPLE", "OTHER")) {
                if (!devices.containsKey(type + ":" + brand)) {
                    throw new IllegalArgumentException("Missing device mapping: " + type + ":" + brand);
                }
            }
        }
        if (!devices.containsKey("WATCH:SAMSUNG")) {
            throw new IllegalArgumentException("Missing device mapping: WATCH:SAMSUNG");
        }
        deviceMappings = Collections.unmodifiableMap(devices);
        proposalMappings = Collections.unmodifiableMap(proposals);
        sha256 = digest(content);
    }

    public static CatalogCategoryRegistry standard() {
        return Bundled.INSTANCE;
    }

    public static CatalogCategoryRegistry parse(String content) {
        if (content == null) {
            throw new IllegalArgumentException("Catalog registry content must not be null");
        }
        List<String> lines = content.lines().toList();
        if (lines.size() < 3 || !lines.getFirst().equals("# " + VERSION)
                || !lines.get(1).equals(HEADER)) {
            throw new IllegalArgumentException("Unsupported catalog registry version or header");
        }
        Map<String, Definition> entries = new LinkedHashMap<>();
        for (int index = 2; index < lines.size(); index++) {
            Definition definition = parseDefinition(lines.get(index), index + 1);
            if (entries.putIfAbsent(definition.code(), definition) != null) {
                throw new IllegalArgumentException("Duplicate category: " + definition.code());
            }
        }
        return new CatalogCategoryRegistry(entries, content);
    }

    public List<Definition> definitions() {
        return List.copyOf(definitions.values());
    }

    public Optional<Definition> find(String code) {
        return Optional.ofNullable(definitions.get(code));
    }

    public Definition require(String code) {
        return find(code).orElseThrow(() -> new IllegalArgumentException(
                "Unknown catalog category: " + code));
    }

    public String sha256() {
        return sha256;
    }

    public Map<String, String> proposalCategories(String domain) {
        if (!PROPOSAL_DOMAINS.contains(domain)) {
            throw new IllegalArgumentException("Unknown proposal domain: " + domain);
        }
        Map<String, String> result = new LinkedHashMap<>();
        proposalMappings.forEach((key, code) -> {
            if (key.startsWith(domain + ":")) {
                result.put(key.substring(domain.length() + 1), code);
            }
        });
        return Collections.unmodifiableMap(result);
    }

    public Optional<String> deviceCategory(String deviceType, String brandCode) {
        if (!DEVICE_TYPES.contains(deviceType)) {
            throw new IllegalArgumentException("Unknown device type: " + deviceType);
        }
        String brand = brandCode == null ? "" : brandCode.strip().toUpperCase(Locale.ROOT);
        if (brand.isEmpty() || brand.equals("UNKNOWN") || brand.equals("OTHER")) {
            return Optional.empty();
        }
        return Optional.ofNullable(deviceMappings.getOrDefault(
                deviceType + ":" + brand, deviceMappings.get(deviceType + ":OTHER")));
    }

    private static Definition parseDefinition(String line, int lineNumber) {
        String[] cells = line.split("\t", -1);
        if (cells.length != 13) {
            throw new IllegalArgumentException("Invalid registry columns at line " + lineNumber);
        }
        for (String cell : cells) {
            if (!cell.equals(cell.strip())) {
                throw new IllegalArgumentException("Untrimmed registry value at line " + lineNumber);
            }
        }
        for (int index = 8; index < cells.length; index++) {
            if (cells[index].equals("-")) {
                cells[index] = "";
            }
        }
        if (!token(cells[0]) || cells[1].isBlank()) {
            throw new IllegalArgumentException("Invalid category code or name at line " + lineNumber);
        }
        AnalyticsCategoryKind kind = AnalyticsCategoryKind.valueOf(cells[2]);
        DeviceFamily family = DeviceFamily.valueOf(cells[3]);
        boolean phone = flag(cells[4]);
        boolean device = flag(cells[5]);
        boolean additional = flag(cells[6]);
        Scope scope = Scope.valueOf(cells[7]);
        boolean expectedAdditional = Set.of(AnalyticsCategoryKind.ACCESSORY,
                AnalyticsCategoryKind.SERVICE, AnalyticsCategoryKind.WARRANTY,
                AnalyticsCategoryKind.PROTECTION).contains(kind);
        if (device != (kind == AnalyticsCategoryKind.DEVICE) || (phone && (!device
                || !Set.of(DeviceFamily.IPHONE, DeviceFamily.SAMSUNG).contains(family)))
                || additional != expectedAdditional
                || (scope == Scope.DEFERRED && kind != AnalyticsCategoryKind.SERVICE)) {
            throw new IllegalArgumentException("Contradictory financial flags: " + cells[0]);
        }
        if (cells[8].isEmpty() != cells[9].isEmpty()
                || (!cells[8].isEmpty() && (!device || !DEVICE_TYPES.contains(cells[8])
                || !Set.of("APPLE", "SAMSUNG", "OTHER").contains(cells[9])))) {
            throw new IllegalArgumentException("Invalid device mapping: " + cells[0]);
        }
        Set<String> functions = tokens(cells[11]);
        Set<String> proposals = proposalKeys(cells[10], functions);
        Set<ProductConditionType> conditions = new LinkedHashSet<>();
        for (String condition : tokens(cells[12])) {
            ProductConditionType value = ProductConditionType.valueOf(condition);
            if (value == ProductConditionType.UNKNOWN) {
                throw new IllegalArgumentException("UNKNOWN is not a confirmed condition");
            }
            conditions.add(value);
        }
        return new Definition(cells[0], cells[1], kind, family, phone, device, additional,
                scope, cells[8], cells[9], proposals, functions, conditions);
    }

    private static Set<String> proposalKeys(String value, Set<String> functions) {
        Set<String> result = new LinkedHashSet<>();
        if (value.isEmpty()) {
            return result;
        }
        for (String key : value.split("\\|", -1)) {
            String[] parts = key.split(":", -1);
            if (parts.length != 2 || !PROPOSAL_DOMAINS.contains(parts[0]) || !token(parts[1])
                    || !functions.contains(parts[1]) || !result.add(key)) {
                throw new IllegalArgumentException("Invalid proposal key: " + key);
            }
        }
        return result;
    }

    private static Set<String> tokens(String value) {
        Set<String> result = new LinkedHashSet<>();
        if (!value.isEmpty()) {
            for (String item : value.split("\\|", -1)) {
                if (!token(item) || !result.add(item)) {
                    throw new IllegalArgumentException("Invalid or duplicate registry token: " + item);
                }
            }
        }
        return result;
    }

    private static boolean token(String value) {
        return value.matches("[A-Z][A-Z0-9_]+");
    }

    private static boolean flag(String value) {
        if (!value.equals("true") && !value.equals("false")) {
            throw new IllegalArgumentException("Invalid registry boolean: " + value);
        }
        return value.equals("true");
    }

    private static void putUnique(Map<String, String> values, String key, String category) {
        if (values.putIfAbsent(key, category) != null) {
            throw new IllegalArgumentException("Duplicate registry mapping: " + key);
        }
    }

    private static String digest(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static CatalogCategoryRegistry load() {
        try (InputStream input = CatalogCategoryRegistry.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing catalog registry resource: " + RESOURCE);
            }
            return parse(StandardCharsets.UTF_8.newDecoder()
                    .decode(ByteBuffer.wrap(input.readAllBytes())).toString());
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot read catalog registry", exception);
        }
    }

    private static final class Bundled {
        private static final CatalogCategoryRegistry INSTANCE = load();
    }
}
