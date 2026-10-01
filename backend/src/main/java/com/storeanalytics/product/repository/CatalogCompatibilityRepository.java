package com.storeanalytics.product.repository;

import com.storeanalytics.product.exception.ProductNotFoundException;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.Coverage;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.IdentityType;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.Subject;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.Target;
import com.storeanalytics.product.model.ProductSourceKind;
import com.storeanalytics.product.service.CatalogCompatibilityRecords.Action;
import com.storeanalytics.product.service.CatalogCompatibilityRecords.Decision;
import com.storeanalytics.product.service.CatalogCompatibilityRecords.Observation;
import com.storeanalytics.product.service.CatalogCompatibilityRecords.Origin;
import com.storeanalytics.product.service.CatalogCompatibilityRecords.Request;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogCompatibilityRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public CatalogCompatibilityRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Observation observe(UUID productId, boolean lock) {
        if (lock) {
            jdbc.query("SELECT id FROM products WHERE id = :id FOR UPDATE",
                    Map.of("id", productId), (row, index) -> row.getObject(1, UUID.class));
            jdbc.query("""
                    SELECT id FROM source_product_groups
                    WHERE id = (SELECT source_group_id FROM products WHERE id = :id) FOR SHARE
                    """, Map.of("id", productId), (row, index) -> row.getObject(1, UUID.class));
        }
        return jdbc.query("""
                SELECT p.id, p.connection_id, c.connection_key, p.external_id, p.code, p.name, g.path
                FROM products p JOIN integration_connections c ON c.id = p.connection_id
                LEFT JOIN source_product_groups g ON g.id = p.source_group_id
                WHERE p.id = :id AND p.is_active AND c.is_active
                  AND p.source_system = 'LIVESKLAD' AND p.source_kind = 'PRODUCT'
                """, Map.of("id", productId), (row, index) -> new Observation(
                row.getObject("id", UUID.class), row.getObject("connection_id", UUID.class),
                new Subject(row.getString("connection_key"), ProductSourceKind.PRODUCT, IdentityType.EXTERNAL_ID,
                        row.getString("external_id")), row.getString("code"),
                row.getString("name"), row.getString("path")))
                .stream().findFirst().orElseThrow(() -> new ProductNotFoundException(productId));
    }

    public Optional<Decision> latest(UUID productId) {
        return jdbc.query("SELECT * FROM catalog_compatibility_history WHERE product_id = :id "
                        + "ORDER BY revision DESC LIMIT 1", Map.of("id", productId), this::decision)
                .stream().findFirst();
    }

    public Optional<Decision> effective(UUID productId, Instant occurredAt) {
        return jdbc.query("""
                SELECT * FROM catalog_compatibility_history
                WHERE product_id = :id AND recorded_at <= :at
                  AND (valid_to IS NULL OR :at < valid_to)
                ORDER BY revision DESC LIMIT 1
                """, Map.of("id", productId, "at", Timestamp.from(occurredAt.truncatedTo(ChronoUnit.MICROS))),
                this::decision)
                .stream().findFirst();
    }

    public Decision append(Observation observation, long revision, Request request, UUID actorId) {
        UUID id = UUID.randomUUID();
        var parameters = new MapSqlParameterSource()
                .addValue("id", id).addValue("product", observation.productId())
                .addValue("connection", observation.connectionId())
                .addValue("connectionKey", observation.subject().connectionKey())
                .addValue("external", observation.subject().id()).addValue("name", observation.name())
                .addValue("group", observation.groupPath()).addValue("fingerprint", observation.fingerprint())
                .addValue("revision", revision).addValue("action", request.action().name())
                .addValue("coverage", request.coverage().name())
                .addValue("targets", request.targets().stream().map(Enum::name).collect(Collectors.joining(",")))
                .addValue("actor", actorId).addValue("reason", request.reason())
                .addValue("origin", request.origin().name())
                .addValue("hash", request.evidenceSha256()).addValue("key", request.evidenceKey());
        jdbc.update("""
                INSERT INTO catalog_compatibility_decisions
                    (id, product_id, connection_id, connection_key_snapshot, external_id_snapshot,
                     product_name_snapshot, group_path_snapshot, observation_fingerprint, revision,
                     action, coverage, targets, actor_id, reason, origin, evidence_sha256, evidence_key)
                VALUES (:id, :product, :connection, :connectionKey, :external, :name, :group, :fingerprint,
                    :revision, :action, :coverage, string_to_array(:targets, ','),
                    :actor, :reason, :origin, :hash, :key)
                """, parameters);
        return jdbc.queryForObject("SELECT * FROM catalog_compatibility_history WHERE id = :id",
                Map.of("id", id), this::decision);
    }

    private Decision decision(ResultSet row, int ignored) throws SQLException {
        Timestamp validTo = row.getTimestamp("valid_to");
        return new Decision(row.getObject("id", UUID.class), row.getObject("product_id", UUID.class),
                new Subject(row.getString("connection_key_snapshot"), ProductSourceKind.PRODUCT,
                        IdentityType.EXTERNAL_ID, row.getString("external_id_snapshot")),
                row.getString("observation_fingerprint"), row.getLong("revision"),
                Action.valueOf(row.getString("action")), Coverage.valueOf(row.getString("coverage")),
                targets(row), row.getObject("actor_id", UUID.class), row.getString("reason"),
                Origin.valueOf(row.getString("origin")), row.getString("evidence_sha256"),
                row.getString("evidence_key"),
                row.getTimestamp("recorded_at").toInstant(), validTo == null ? null : validTo.toInstant());
    }

    private Set<Target> targets(ResultSet row) throws SQLException {
        var array = row.getArray("targets");
        try {
            return Arrays.stream((String[]) array.getArray()).map(Target::valueOf)
                    .collect(Collectors.toUnmodifiableSet());
        } finally {
            array.free();
        }
    }
}
