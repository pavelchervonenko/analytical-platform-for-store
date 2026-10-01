import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Private diagnostic: replays exported catalog facts in ephemeral PostgreSQL, never production. */
public final class CatalogSnapshotMigrationRehearsal {
    private static final ObjectMapper JSON = new ObjectMapper();

    private CatalogSnapshotMigrationRehearsal() { }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception exception) {
            Throwable cause = exception;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            String code = cause instanceof java.sql.SQLException sql ? sql.getSQLState()
                    : cause.getClass().getSimpleName();
            String location = cause instanceof org.postgresql.util.PSQLException pg
                    && pg.getServerErrorMessage() != null
                    ? pg.getServerErrorMessage().getTable() + "."
                        + pg.getServerErrorMessage().getColumn() : "unknown";
            System.err.println("Catalog rehearsal failed: " + code + " at " + location
                    + (exception instanceof IllegalStateException ? " - " + exception.getMessage() : ""));
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected one private snapshot.jsonl path");
        }
        Path snapshot = Path.of(args[0]);
        verifyDigest(snapshot);
        Map<String, List<ObjectNode>> rows = loadRows(snapshot);
        try (var postgres = new PostgreSQLContainer("postgres:16-alpine")) {
            postgres.start();
            Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword()).locations("classpath:db/migration").target("51").load().migrate();
            try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword())) {
                connection.setAutoCommit(false);
                hydrate(connection, rows);
                connection.commit();
                List<String> before = attach(connection, "attach_rate_item_facts_v3", false);
                String positionsBefore = fingerprint(connection, """
                        SELECT id::text, product_id::text, analytics_category_id::text,
                               condition_type_snapshot, classification_version,
                               quantity::text, net_amount::text, cost_amount::text
                        FROM sales_document_items ORDER BY id
                        """);
                String assignmentsBefore = fingerprint(connection, """
                        SELECT id::text, product_id::text, analytics_category_id::text,
                               condition_type, valid_from::text, valid_to::text
                        FROM product_category_assignments ORDER BY id
                        """);
                String payrollBefore = fingerprint(connection, """
                        SELECT id::text, product_id::text, payroll_category_code,
                               valid_from::text, valid_to::text
                        FROM product_payroll_category_assignments ORDER BY id
                        """);
                String documentsBefore = fingerprint(connection, """
                        SELECT id::text, store_id::text, document_kind, business_date::text,
                               occurred_at::text, net_amount::text, cost_amount::text
                        FROM sales_documents ORDER BY id
                        """);
                String categoryFlagsBefore = fingerprint(connection, """
                        SELECT code, category_kind, device_family, counts_as_phone::text,
                               counts_as_device::text, counts_as_additional_revenue::text,
                               payroll_category_code, is_active::text
                        FROM analytics_categories WHERE id IN (
                            SELECT DISTINCT analytics_category_id FROM sales_document_items)
                        ORDER BY code
                        """);
                connection.setAutoCommit(true);
                Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                        postgres.getPassword()).locations("classpath:db/migration").target("55").load().migrate();
                List<String> warrantyBefore = attach(connection, "attach_rate_item_facts_v4", false);
                Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                        postgres.getPassword()).locations("classpath:db/migration").load().migrate();
                List<String> after = attach(connection, "attach_rate_item_facts_v3_catalog", true);
                List<String> warrantyAfter = attach(connection, "attach_rate_item_facts_v4_catalog", true);
                String positionsAfter = fingerprint(connection, """
                        SELECT id::text, product_id::text, analytics_category_id::text,
                               condition_type_snapshot, classification_version,
                               quantity::text, net_amount::text, cost_amount::text
                        FROM sales_document_items ORDER BY id
                        """);
                String assignmentsAfter = fingerprint(connection, """
                        SELECT id::text, product_id::text, analytics_category_id::text,
                               condition_type, valid_from::text, valid_to::text
                        FROM product_category_assignments ORDER BY id
                        """);
                String payrollAfter = fingerprint(connection, """
                        SELECT id::text, product_id::text, payroll_category_code,
                               valid_from::text, valid_to::text
                        FROM product_payroll_category_assignments ORDER BY id
                        """);
                String documentsAfter = fingerprint(connection, """
                        SELECT id::text, store_id::text, document_kind, business_date::text,
                               occurred_at::text, net_amount::text, cost_amount::text
                        FROM sales_documents ORDER BY id
                        """);
                String categoryFlagsAfter = fingerprint(connection, """
                        SELECT code, category_kind, device_family, counts_as_phone::text,
                               counts_as_device::text, counts_as_additional_revenue::text,
                               payroll_category_code, is_active::text
                        FROM analytics_categories WHERE id IN (
                            SELECT DISTINCT analytics_category_id FROM sales_document_items)
                        ORDER BY code
                        """);
                if (!positionsBefore.equals(positionsAfter) || !assignmentsBefore.equals(assignmentsAfter)
                        || !payrollBefore.equals(payrollAfter) || !documentsBefore.equals(documentsAfter)
                        || !categoryFlagsBefore.equals(categoryFlagsAfter)) {
                    throw new IllegalStateException("Saved document, sale, assignment or category metadata changed");
                }
                if (!before.equals(after)) {
                    int differences = 0;
                    for (int index = 0; index < Math.max(before.size(), after.size()); index++) {
                        if (index >= before.size() || index >= after.size()
                                || !before.get(index).equals(after.get(index))) {
                            differences++;
                        }
                    }
                    throw new IllegalStateException("Published attach aggregates differ in "
                            + differences + " store/day/metric rows");
                }
                if (!warrantyBefore.equals(warrantyAfter)) {
                    int differences = 0;
                    String metric = "unknown";
                    for (int index = 0; index < Math.max(warrantyBefore.size(), warrantyAfter.size()); index++) {
                        if (index >= warrantyBefore.size() || index >= warrantyAfter.size()
                                || !warrantyBefore.get(index).equals(warrantyAfter.get(index))) {
                            differences++;
                            if (index < warrantyBefore.size()) {
                                metric = warrantyBefore.get(index).split(":")[2];
                            }
                        }
                    }
                    throw new IllegalStateException("Published warranty attach aggregates differ in "
                            + differences + " store/day/metric rows, last metric=" + metric + " (before="
                            + warrantyBefore.size() + ", after=" + warrantyAfter.size() + ")");
                }
                System.out.println("Snapshot checksum valid; replayed "
                        + rows.getOrDefault("sales_document_items", List.of()).size()
                        + " sale items; " + before.size() + " v3 and "
                        + warrantyBefore.size() + " v4 published attach aggregate rows and saved "
                        + "documents/positions/assignments/category flags unchanged");
            }
        }
    }

    private static void verifyDigest(Path snapshot) throws Exception {
        String manifest = Files.readString(snapshot.resolveSibling("snapshot.sha256"));
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(snapshot)));
        if (!manifest.startsWith(actual + "  snapshot.jsonl")) {
            throw new IllegalArgumentException("Private snapshot checksum mismatch");
        }
    }

    private static Map<String, List<ObjectNode>> loadRows(Path snapshot) throws Exception {
        Map<String, List<ObjectNode>> rows = new HashMap<>();
        try (var lines = Files.lines(snapshot, StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                try {
                    var record = JSON.readTree(line);
                    if (record.has("data") && record.get("data") instanceof ObjectNode data) {
                        rows.computeIfAbsent(record.get("kind").asText(), ignored -> new ArrayList<>())
                                .add(data);
                    }
                } catch (Exception exception) {
                    throw new IllegalArgumentException("Invalid private snapshot row", exception);
                }
            });
        }
        return rows;
    }

    private static void hydrate(Connection connection, Map<String, List<ObjectNode>> rows) throws Exception {
        String connectionId;
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT id::text FROM integration_connections
                     WHERE connection_key='livesklad-default'
                     """)) {
            if (!result.next()) {
                throw new IllegalStateException("Fixture connection missing");
            }
            connectionId = result.getString(1);
        }
        Map<String, String> categoryIds = new HashMap<>();
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT id::text,code FROM analytics_categories")) {
            while (result.next()) {
                categoryIds.put(result.getString(2), result.getString(1));
            }
        }
        Map<String, String> originalCategoryCodes = new HashMap<>();
        for (ObjectNode category : rows.get("analytics_categories")) {
            originalCategoryCodes.put(category.get("id").asText(), category.get("code").asText());
        }
        insert(connection, "stores", "id,connection_id,external_id,name,is_active,timezone,business_day_start",
                map(rows.get("stores"), row -> row.put("connection_id", connectionId)));
        insert(connection, "employees", "id,connection_id,external_id,full_name,is_active",
                map(rows.get("employees"), row -> {
                    row.put("connection_id", connectionId);
                    row.put("full_name", "Rehearsal employee " + row.get("id").asText());
                }));
        String runId = UUID.randomUUID().toString();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO sync_runs(id,connection_id,source_system,trigger_type,sync_scope,
                    status,started_at,finished_at)
                VALUES (?::uuid,?::uuid,'LIVESKLAD','MANUAL','SALES','SUCCESS',now(),now())
                """)) {
            statement.setString(1, runId);
            statement.setString(2, connectionId);
            statement.executeUpdate();
        }
        insert(connection, "products", "id,connection_id,external_id,name,source_kind,is_active,code,sku,version",
                map(rows.get("products"), row -> row.put("connection_id", connectionId)));
        insert(connection, "product_category_assignments", "id,product_id,analytics_category_id,"
                + "condition_type,assignment_source,rule_version,valid_from,valid_to,created_at",
                map(rows.get("product_category_assignments"), row -> mapCategory(row,
                        originalCategoryCodes, categoryIds)));
        insert(connection, "product_payroll_category_assignments", "id,product_id,payroll_category_code,"
                + "valid_from,valid_to,version,created_at,updated_at,change_reason",
                map(rows.get("product_payroll_category_assignments"),
                        row -> row.put("change_reason", "Private snapshot rehearsal")));

        List<ObjectNode> documents = rows.get("sales_documents");
        Set<String> documentIds = ids(documents);
        List<ObjectNode> orderedDocuments = map(documents, row -> {
            row.put("connection_id", connectionId);
            row.put("last_sync_run_id", runId);
            if (row.hasNonNull("original_document_id")
                    && !documentIds.contains(row.get("original_document_id").asText())) {
                row.putNull("original_document_id");
            }
        });
        orderedDocuments.sort(Comparator.comparing(row -> "RETURN".equals(
                row.get("document_kind").asText())));
        insert(connection, "sales_documents", "id,connection_id,external_id,store_id,"
                + "original_document_id,document_kind,source_document_type,occurred_at,"
                + "business_date,employee_id,net_amount,cost_amount,is_deleted,last_sync_run_id,version",
                orderedDocuments);

        List<ObjectNode> items = rows.get("sales_document_items");
        Set<String> itemIds = ids(items);
        Map<String, String> documentKinds = new HashMap<>();
        for (ObjectNode document : documents) {
            documentKinds.put(document.get("id").asText(), document.get("document_kind").asText());
        }
        List<ObjectNode> orderedItems = map(items, row -> {
            mapCategory(row, originalCategoryCodes, categoryIds);
            if (row.hasNonNull("original_item_id")
                    && !itemIds.contains(row.get("original_item_id").asText())) {
                row.putNull("original_item_id");
            }
        });
        orderedItems.sort(Comparator.comparing(row -> "RETURN".equals(
                documentKinds.get(row.get("sales_document_id").asText()))));
        insert(connection, "sales_document_items", "id,sales_document_id,external_id,product_id,"
                + "product_name_snapshot,analytics_category_id,condition_type_snapshot,quantity,"
                + "unit_price,gross_amount,discount_amount,net_amount,cost_amount,cost_quality,"
                + "is_deleted,is_work,original_item_id,classification_version,version", orderedItems);
    }

    private static void mapCategory(ObjectNode row, Map<String, String> originalCodes,
                                    Map<String, String> targetIds) {
        String code = originalCodes.get(row.get("analytics_category_id").asText());
        String target = targetIds.get(code);
        if (target == null) {
            throw new IllegalStateException("Missing saved analytical category in baseline schema");
        }
        row.put("analytics_category_id", target);
    }

    private static Set<String> ids(List<ObjectNode> rows) {
        Set<String> ids = new HashSet<>();
        for (ObjectNode row : rows) {
            ids.add(row.get("id").asText());
        }
        return ids;
    }

    private static List<ObjectNode> map(List<ObjectNode> original,
                                        java.util.function.Consumer<ObjectNode> change) {
        List<ObjectNode> copy = new ArrayList<>();
        for (ObjectNode row : original) {
            ObjectNode result = row.deepCopy();
            change.accept(result);
            copy.add(result);
        }
        return copy;
    }

    private static void insert(Connection connection, String table, String columns,
                               List<ObjectNode> rows) throws Exception {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        String query = "INSERT INTO " + table + "(" + columns + ") SELECT " + columns
                + " FROM jsonb_populate_record(NULL::" + table + ",?::jsonb)";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            int batch = 0;
            for (ObjectNode row : rows) {
                statement.setString(1, JSON.writeValueAsString(row));
                statement.addBatch();
                if (++batch == 500) {
                    statement.executeBatch();
                    batch = 0;
                }
            }
            if (batch > 0) {
                statement.executeBatch();
            }
        }
    }

    private static List<String> attach(Connection connection, String view, boolean catalog)
            throws Exception {
        List<String> rows = new ArrayList<>();
        String numerator = catalog ? "definition.metric_code=ANY(fact.numerator_metric_codes)"
                : "definition.metric_code=fact.numerator_metric_code";
        String query = """
                SELECT fact.store_id::text,fact.business_date::text,definition.metric_code,
                    COALESCE(sum(fact.net_quantity) FILTER (WHERE %s),0)::text,
                    COALESCE(sum(fact.net_quantity) FILTER (
                        WHERE definition.metric_code=ANY(fact.denominator_metric_codes)),0)::text
                FROM %s fact CROSS JOIN attach_rate_metric_definitions_v3 definition
                WHERE definition.sort_order<=14
                GROUP BY fact.store_id,fact.business_date,definition.sort_order,definition.metric_code
                ORDER BY fact.store_id,fact.business_date,definition.sort_order
                """.formatted(numerator, view);
        try (var statement = connection.createStatement(); var result = statement.executeQuery(query)) {
            while (result.next()) {
                rows.add(result.getString(1) + ":" + result.getString(2) + ":" + result.getString(3)
                        + ":" + result.getString(4) + ":" + result.getString(5));
            }
        }
        return rows;
    }

    private static String fingerprint(Connection connection, String query) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var statement = connection.createStatement(); var result = statement.executeQuery(query)) {
            ResultSet metadata = result;
            while (result.next()) {
                for (int index = 1; index <= metadata.getMetaData().getColumnCount(); index++) {
                    String value = result.getString(index);
                    digest.update((value == null ? "<NULL>" : value).getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                }
                digest.update((byte) 10);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
