package com.storeanalytics.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.product.model.CatalogCompatibilityEvidence.Context;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.Coverage;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.Target;
import com.storeanalytics.product.repository.CatalogCompatibilityRepository;
import com.storeanalytics.product.service.CatalogAccessoryAttachPolicy.Outcome;
import com.storeanalytics.product.service.CatalogAccessoryAttachPolicy.Reason;
import com.storeanalytics.product.service.CatalogAccessoryAttachPolicy.Role;
import com.storeanalytics.product.service.CatalogCompatibilityRecords.Action;
import com.storeanalytics.product.service.CatalogCompatibilityRecords.Observation;
import com.storeanalytics.product.service.CatalogCompatibilityRecords.Origin;
import com.storeanalytics.product.service.CatalogCompatibilityRecords.Request;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class CatalogCompatibilityPersistenceIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    private static NamedParameterJdbcTemplate jdbc;
    private static CatalogCompatibilityRepository repository;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        repository = new CatalogCompatibilityRepository(jdbc);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    void storesProspectiveHistoryRevokesWithoutFallingBackAndLeavesOtherFactsUntouched() {
        Fixture fixture = fixture();
        long salesBefore = count("sales_document_items");
        long assignmentsBefore = count("product_category_assignments");
        var decision = transactions.execute(status -> repository.append(repository.observe(fixture.product(), true), 1,
                confirm(Coverage.EXCLUSIVE, Target.APPLE_WATCH), fixture.actor()));
        assertThat(repository.effective(fixture.product(), decision.recordedAt().minusNanos(1))).isEmpty();
        assertThat(repository.effective(fixture.product(), decision.recordedAt())).contains(decision);
        var projection = new CatalogCompatibilityProjectionService(repository);
        var observation = repository.observe(fixture.product(), false);
        Context sale = new Context(observation.subject(), observation.fingerprint(), decision.recordedAt());
        assertThat(projection.forNewSaleFact(fixture.product(), "CHARGER_CABLE", sale).role())
                .isEqualTo(Role.ACCESSORY_APPLE_WATCH);
        var revoked = transactions.execute(status -> repository.append(observation, 2,
                new Request(Action.REVOKE, Coverage.UNDETERMINED, List.of(), "Synthetic revoke",
                        Origin.DIRECT_REVIEW, null, null), fixture.actor()));
        assertThat(revoked.recordedAt()).isAfter(decision.recordedAt());
        assertThat(repository.effective(fixture.product(), decision.recordedAt()).orElseThrow().validTo())
                .isEqualTo(revoked.recordedAt());
        var current = new Context(observation.subject(), observation.fingerprint(), revoked.recordedAt());
        assertThat(projection.forNewSaleFact(fixture.product(), "CHARGER_CABLE", current).reason())
                .isEqualTo(Reason.CONFIRMATION_REVOKED);
        assertThat(projection.forNewSaleFact(fixture.product(), "CHARGER_CABLE", sale).role())
                .isEqualTo(Role.ACCESSORY_APPLE_WATCH);
        assertThat(count("sales_document_items")).isEqualTo(salesBefore);
        assertThat(count("product_category_assignments")).isEqualTo(assignmentsBefore);
    }

    @Test
    void databaseRejectsStaleObservationWrongActorSkippedRevisionAndHistoryMutation() {
        Fixture fixture = fixture();
        var observation = repository.observe(fixture.product(), false);
        assertThatThrownBy(() -> repository.append(observation, 2, confirm(Coverage.EXCLUSIVE, Target.APPLE_WATCH),
                fixture.actor())).isInstanceOf(DataAccessException.class);
        jdbc.update("UPDATE products SET name = 'Changed synthetic name' WHERE id = :id",
                Map.of("id", fixture.product()));
        assertThatThrownBy(() -> repository.append(observation, 1, confirm(Coverage.EXCLUSIVE, Target.APPLE_WATCH),
                fixture.actor())).isInstanceOf(DataAccessException.class);
        var fresh = repository.observe(fixture.product(), false);
        jdbc.update("UPDATE app_users SET role = 'MANAGER' WHERE id = :id", Map.of("id", fixture.actor()));
        assertThatThrownBy(() -> repository.append(fresh, 1, confirm(Coverage.EXCLUSIVE, Target.APPLE_WATCH),
                fixture.actor())).isInstanceOf(DataAccessException.class);
        jdbc.update("UPDATE app_users SET role = 'ADMIN' WHERE id = :id", Map.of("id", fixture.actor()));
        var saved = repository.append(fresh, 1, confirm(Coverage.EXCLUSIVE, Target.APPLE_WATCH), fixture.actor());
        assertThatThrownBy(() -> jdbc.update("UPDATE catalog_compatibility_decisions SET reason = 'Changed' "
                + "WHERE id = :id", Map.of("id", saved.id()))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM catalog_compatibility_decisions WHERE id = :id",
                Map.of("id", saved.id()))).isInstanceOf(DataAccessException.class);
    }

    @Test
    void oldApprovalIsAdoptedNowWithExactCodeAndWithoutInventingExclusiveCoverage() {
        Fixture fixture = fixture();
        var observation = repository.observe(fixture.product(), false);
        Request adoption = new Request(Action.CONFIRM, Coverage.UNDETERMINED, List.of(Target.APPLE_WATCH),
                "Adopt synthetic owner evidence", Origin.LEGACY_ADOPTION,
                "a".repeat(64), "PRODUCT:" + observation.code());
        var decision = repository.append(observation, 1, adoption, fixture.actor());
        assertThat(decision.origin()).isEqualTo(Origin.LEGACY_ADOPTION);
        assertThat(decision.actorId()).isEqualTo(fixture.actor());
        assertThat(decision.evidenceSha256()).isEqualTo("a".repeat(64));
        var result = new CatalogCompatibilityProjectionService(repository).forNewSaleFact(fixture.product(),
                "CHARGER_CABLE", new Context(observation.subject(), observation.fingerprint(), decision.recordedAt()));
        assertThat(result.outcome()).isEqualTo(Outcome.REVIEW_PRODUCT);
        assertThat(result.reason()).isEqualTo(Reason.WATCH_EXCLUSIVITY_UNCONFIRMED);
        Request wrongKey = new Request(Action.CONFIRM, Coverage.UNDETERMINED, List.of(Target.APPLE_WATCH),
                "Wrong evidence", Origin.LEGACY_ADOPTION, "a".repeat(64), "PRODUCT:other");
        assertThatThrownBy(() -> repository.append(observation, 2, wrongKey, fixture.actor()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void nameChangeInvalidatesConfirmationWithoutRewritingItsEvidence() {
        Fixture fixture = fixture();
        var observation = repository.observe(fixture.product(), false);
        var decision = repository.append(observation, 1,
                confirm(Coverage.EXCLUSIVE, Target.APPLE_WATCH), fixture.actor());
        jdbc.update("UPDATE products SET name = 'New synthetic observation' WHERE id = :id",
                Map.of("id", fixture.product()));
        var changed = repository.observe(fixture.product(), false);
        var result = new CatalogCompatibilityProjectionService(repository).forNewSaleFact(fixture.product(),
                "CHARGER_CABLE", new Context(changed.subject(), changed.fingerprint(), decision.recordedAt()));
        assertThat(result.reason()).isEqualTo(Reason.CONFIRMATION_NOT_APPLICABLE);
        assertThat(repository.latest(fixture.product()).orElseThrow().fingerprint())
                .isEqualTo(observation.fingerprint());
    }

    @Test
    void groupChangeRequiresNewEvidenceWhileCodeChangeAloneDoesNotChangeSemanticFingerprint() {
        Fixture fixture = fixture();
        var observation = repository.observe(fixture.product(), false);
        repository.append(observation, 1, confirm(Coverage.EXCLUSIVE, Target.APPLE_WATCH), fixture.actor());
        jdbc.update("UPDATE products SET code = 'another-code' WHERE id = :id", Map.of("id", fixture.product()));
        assertThat(repository.observe(fixture.product(), false).fingerprint()).isEqualTo(observation.fingerprint());
        UUID group = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO source_product_groups(id,connection_id,path,name)
                VALUES (:id,:connection,:path,'Synthetic group')
                """, Map.of("id", group, "connection", observation.connectionId(), "path", "synthetic-" + group));
        jdbc.update("UPDATE products SET source_group_id = :group WHERE id = :id",
                Map.of("group", group, "id", fixture.product()));
        var changed = repository.observe(fixture.product(), false);
        assertThat(changed.fingerprint()).isNotEqualTo(observation.fingerprint());
        assertThatThrownBy(() -> repository.append(observation, 2,
                confirm(Coverage.EXCLUSIVE, Target.APPLE_WATCH), fixture.actor()))
                .isInstanceOf(DataAccessException.class);
        var accepted = repository.append(changed, 2,
                confirm(Coverage.EXCLUSIVE, Target.APPLE_WATCH), fixture.actor());
        assertThat(accepted.fingerprint()).isEqualTo(changed.fingerprint());
        jdbc.update("UPDATE products SET is_active = false WHERE id = :id", Map.of("id", fixture.product()));
        assertThatThrownBy(() -> repository.append(changed, 3,
                confirm(Coverage.EXCLUSIVE, Target.APPLE_WATCH), fixture.actor()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void databaseSerializesCompetingRevisionsAndIgnoresClientSuppliedApprovalDate() throws Exception {
        Fixture fixture = fixture();
        Observation observation = repository.observe(fixture.product(), false);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> writer = () -> {
            start.await(5, TimeUnit.SECONDS);
            try {
                transactions.execute(status -> {
                    jdbc.getJdbcTemplate().execute("SET LOCAL lock_timeout = '5s'");
                    return repository.append(observation, 1,
                            confirm(Coverage.EXCLUSIVE, Target.APPLE_WATCH), fixture.actor());
                });
                return true;
            } catch (DataAccessException expected) {
                return false;
            }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(writer);
            var second = executor.submit(writer);
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        var previous = repository.latest(fixture.product()).orElseThrow();
        jdbc.update("""
                INSERT INTO catalog_compatibility_decisions
                  (product_id,connection_id,connection_key_snapshot,external_id_snapshot,product_name_snapshot,
                   group_path_snapshot,observation_fingerprint,revision,action,coverage,targets,actor_id,reason,
                   origin,recorded_at)
                SELECT product_id,connection_id,connection_key_snapshot,external_id_snapshot,product_name_snapshot,
                   group_path_snapshot,observation_fingerprint,2,action,coverage,targets,actor_id,
                   'Retry with fake date',
                   origin,:past FROM catalog_compatibility_decisions WHERE id = :id
                """, Map.of("past", Timestamp.from(Instant.EPOCH), "id", previous.id()));
        assertThat(repository.latest(fixture.product()).orElseThrow().recordedAt()).isAfter(previous.recordedAt());
    }

    @Test
    void databaseTargetVocabularyMatchesJavaAndRejectsAmbiguousArrayShapes() {
        for (Target target : Target.values()) {
            assertThat(jdbc.queryForObject("SELECT catalog_compatibility_targets_valid(ARRAY[:target], 'UNDETERMINED')",
                    Map.of("target", target.name()), Boolean.class)).isTrue();
        }
        assertThat(jdbc.queryForObject(
                "SELECT catalog_compatibility_targets_valid(ARRAY['WATCH_TYPO'], 'UNDETERMINED')",
                Map.of(), Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT catalog_compatibility_targets_valid(ARRAY['APPLE_WATCH','APPLE_WATCH'], "
                + "'MULTI_DEVICE')", Map.of(), Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT catalog_compatibility_targets_valid(ARRAY['APPLE_WATCH'], 'MULTI_DEVICE')",
                Map.of(), Boolean.class)).isFalse();
    }

    private Fixture fixture() {
        UUID product = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app_users(id,email,password_hash,display_name,role,password_change_required)
                VALUES (:id,:email,'synthetic-not-a-credential','Synthetic actor','ADMIN',false)
                """, Map.of("id", actor, "email", actor + "@example.invalid"));
        jdbc.update("""
                INSERT INTO products(id,connection_id,external_id,code,name,source_kind)
                SELECT :id,id,:external,:code,'Synthetic watch charger','PRODUCT'
                FROM integration_connections WHERE connection_key = 'livesklad-default'
                """, Map.of("id", product, "external", "synthetic-" + product, "code", product.toString()));
        return new Fixture(product, actor);
    }

    private Request confirm(Coverage coverage, Target target) {
        return new Request(Action.CONFIRM, coverage, List.of(target), "Synthetic confirmation",
                Origin.DIRECT_REVIEW, null, null);
    }

    private long count(String table) {
        return jdbc.getJdbcTemplate().queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private record Fixture(UUID product, UUID actor) { }
}
