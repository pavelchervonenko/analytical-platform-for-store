package com.storeanalytics.common.database;

import static com.storeanalytics.interpretation.review.WeeklyReviewTestPayload.snapshotPayload;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class WeeklyAiPlanningOriginMigrationIntegrationTest {
    @Test
    void populatedUpgradeKeepsOldJobsExactAndPreservesAllPriorFieldsAndSnapshots() {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")) {
            postgres.start();
            var source = new DriverManagerDataSource(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(source).locations("classpath:db/migration").target("96").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(source);
            UUID store = UUID.randomUUID();
            UUID snapshot = UUID.randomUUID();
            LocalDate start = LocalDate.parse("2026-09-21");
            jdbc.update("""
                    INSERT INTO stores(id,connection_id,external_id,name,timezone)
                    SELECT ?,id,?,'Synthetic planning upgrade','Europe/Moscow' FROM integration_connections
                    WHERE connection_key='livesklad-default'
                    """, store, store.toString());
            jdbc.update("""
                    INSERT INTO weekly_review_snapshots(id,store_id,period_start,period_end,timezone,
                        revision,report_contract_version,metrics_policy_version,snapshot_policy_version,
                        quality_policy_version,report_state,report_payload,content_hash)
                    VALUES (?,?,?,?,'Europe/Moscow',1,2,'metrics-v4','snapshot-v7','quality-v4','READY',?::jsonb,?)
                    """, snapshot, store, start, start.plusDays(6), snapshotPayload(snapshot, start, 1, "READY"),
                    "a".repeat(64));
            jdbc.update("""
                    INSERT INTO weekly_review_ai_jobs(snapshot_id,prompt_version,content_schema_version,
                        provider_code,requested_model,status,max_attempts,next_attempt_at,deadline_at)
                    VALUES (?,'synthetic-version',4,'YANDEX','synthetic-model','PENDING',2,now(),now()+interval '2h')
                    """, snapshot);
            jdbc.update("UPDATE weekly_review_ai_jobs SET attempt_count=1");
            jdbc.update("""
                    INSERT INTO weekly_review_ai_attempts(job_id,attempt_number,status,request_hash,input_hash,
                        input_payload,response_payload,response_hash,actual_cost,cost_currency,
                        provider_outcome,started_at,finished_at)
                    SELECT id,1,'FAILED',?,?,'{}'::jsonb,'{}',?,2,'RUB','RESPONSE_RECEIVED',now(),now()
                    FROM weekly_review_ai_jobs
                    """, "b".repeat(64), "c".repeat(64), "d".repeat(64));
            jdbc.update("""
                    INSERT INTO weekly_review_ai_response_receipts(attempt_id,receipt_hash,response_payload,
                        response_hash,validation_outcome,validation_violations,actual_cost,cost_currency,received_at)
                    SELECT id,?,'{}',?,'SEMANTIC_INVALID','[]'::jsonb,2,'RUB',now()
                    FROM weekly_review_ai_attempts
                    """, "e".repeat(64), "d".repeat(64));
            String before = jdbc.queryForObject("SELECT to_jsonb(job)::text FROM weekly_review_ai_jobs job",
                    String.class);
            String report = jdbc.queryForObject("SELECT to_jsonb(saved)::text FROM weekly_review_snapshots saved",
                    String.class);
            String attempt = jdbc.queryForObject("SELECT to_jsonb(saved)::text FROM weekly_review_ai_attempts saved",
                    String.class);
            String receipt = jdbc.queryForObject("SELECT to_jsonb(saved)::text "
                    + "FROM weekly_review_ai_response_receipts saved", String.class);
            var migration = Flyway.configure().dataSource(source).locations("classpath:db/migration").load();
            assertThat(migration.migrate().migrationsExecuted).isOne();
            assertThat(jdbc.queryForObject("""
                    SELECT (to_jsonb(job) - ARRAY['planning_origin','automatic_store_id',
                        'automatic_period_start','automatic_period_end'])::text FROM weekly_review_ai_jobs job
                    """, String.class)).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT planning_origin FROM weekly_review_ai_jobs", String.class))
                    .isEqualTo("EXACT");
            assertThat(jdbc.queryForObject("SELECT to_jsonb(saved)::text FROM weekly_review_snapshots saved",
                    String.class)).isEqualTo(report);
            assertThat(jdbc.queryForObject("SELECT to_jsonb(saved)::text FROM weekly_review_ai_attempts saved",
                    String.class)).isEqualTo(attempt);
            assertThat(jdbc.queryForObject("SELECT to_jsonb(saved)::text FROM weekly_review_ai_response_receipts saved",
                    String.class)).isEqualTo(receipt);
            migration.validate();
            assertThat(migration.migrate().migrationsExecuted).isZero();
        }
    }
}
