package com.storeanalytics.interpretation.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.interpretation.generation.LlmAnalysisAttempt;
import com.storeanalytics.interpretation.generation.LlmAnalysisAttemptStatus;
import com.storeanalytics.interpretation.generation.LlmAnalysisAttemptStore;
import com.storeanalytics.interpretation.generation.LlmAnalysisAttemptType;
import com.storeanalytics.interpretation.generation.LlmAnalysisJob;
import com.storeanalytics.interpretation.generation.LlmAnalysisJobLifecycleStore;
import com.storeanalytics.interpretation.generation.LlmAnalysisJobRequest;
import com.storeanalytics.interpretation.generation.LlmAnalysisJobStatus;
import com.storeanalytics.interpretation.generation.LlmAnalysisJobStore;
import com.storeanalytics.interpretation.generation.LlmAnalysisPhase;
import com.storeanalytics.interpretation.generation.LlmAnalysisPhaseTransitionStore;
import com.storeanalytics.interpretation.generation.LlmAnalysisTriggerType;
import com.storeanalytics.interpretation.generation.LlmProviderCallClaimStore;
import com.storeanalytics.interpretation.generation.LlmProviderResponseReceipt;
import com.storeanalytics.interpretation.generation.LlmValidationRetryPromptFactory;
import com.storeanalytics.interpretation.publication.LlmPublicationClaimStore;
import com.storeanalytics.interpretation.publication.LlmPublicationResult;
import com.storeanalytics.interpretation.publication.LlmPublicationStore;
import com.storeanalytics.interpretation.publication.WeeklyPublicationMaterial;
import com.storeanalytics.interpretation.publication.WeeklyPublicationMaterialFactory;
import com.storeanalytics.interpretation.query.WeeklyInterpretationDetailView;
import com.storeanalytics.interpretation.query.WeeklyInterpretationQueryService;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class LlmResponseValidationPipelineIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-03T05:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private LlmAnalysisJobStore jobStore;

    @Autowired
    private LlmProviderCallClaimStore providerClaimStore;

    @Autowired
    private LlmValidationClaimStore validationClaimStore;

    @Autowired
    private LlmAnalysisAttemptStore attemptStore;

    @Autowired
    private LlmAnalysisPhaseTransitionStore providerTransitionStore;

    @Autowired
    private LlmResponseValidationTransitionStore validationTransitionStore;

    @Autowired
    private LlmAnalysisJobLifecycleStore lifecycleStore;

    @Autowired
    private LlmValidationRetryPromptFactory retryPromptFactory;

    @Autowired
    private LlmPublicationClaimStore publicationClaimStore;

    @Autowired
    private LlmPublicationStore publicationStore;

    @Autowired
    private WeeklyPublicationMaterialFactory publicationMaterialFactory;

    @Autowired
    private WeeklyInterpretationQueryService interpretationQueryService;


    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void publicationAtomicallyCreatesInterpretationEventAndSuccessfulJob() {
        PreparedJob prepared = prepareResponse("{\"store\":{}}");
        LlmAnalysisJob validationJob = validationClaimStore.claimNext(
                "validation-worker", Duration.ofMinutes(2), NOW.plusSeconds(3)
        ).orElseThrow();
        validationTransitionStore.complete(
                validationJob.id(),
                prepared.attemptId(),
                "validation-worker",
                LlmResponseValidationResult.valid("{\"store\":{\"headline\":{}}}"),
                NOW.plusSeconds(4)
        );
        LlmAnalysisJob publicationJob = publicationClaimStore.claimNext(
                "publication-worker", Duration.ofMinutes(2), NOW.plusSeconds(5)
        ).orElseThrow();
        LlmAnalysisAttempt attempt = attemptStore.findSuccessfulByJobId(
                prepared.jobId()
        ).orElseThrow();

        LlmPublicationResult result = publicationStore.publish(
                publicationJob.id(),
                attempt.id(),
                "publication-worker",
                publicationMaterialFactory.create(attempt),
                NOW.plusSeconds(6)
        );

        assertThat(result.job().status()).isEqualTo(LlmAnalysisJobStatus.SUCCESS);
        assertThat(result.interpretationRevision()).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM llm_interpretations WHERE id = ?",
                Integer.class,
                result.interpretationId()
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT event_type FROM notification_events WHERE id = ?",
                String.class,
                result.notificationEventId()
        )).isEqualTo("WEEKLY_REPORT_READY");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT content_payload::text FROM llm_interpretations WHERE id = ?",
                String.class,
                result.interpretationId()
        )).isEqualTo("{\"store\": {\"headline\": {}}}");
        assertThat(publicationClaimStore.claimNext(
                "second-publication-worker",
                Duration.ofMinutes(2),
                NOW.plusSeconds(7)
        )).isEmpty();
        UUID storeId = jdbcTemplate.queryForObject(
                "SELECT store_id FROM llm_interpretations WHERE id = ?",
                UUID.class,
                result.interpretationId()
        );
        WeeklyInterpretationDetailView latest = interpretationQueryService.latest(
                storeId
        );
        assertThat(latest.interpretation().id())
                .isEqualTo(result.interpretationId());
        assertThat(latest.interpretation().currentRevision()).isTrue();
        assertThat(latest.interpretation().contentSchemaVersion()).isOne();
        assertThat(latest.content().path("store").isObject()).isTrue();
        assertThat(latest.employees()).singleElement().satisfies(employee -> {
            assertThat(employee.employeeRef()).isEqualTo("E01");
            assertThat(employee.displayName()).isEqualTo("Историческое имя");
        });
        assertThat(interpretationQueryService.list(
                storeId, null, null, 0, 12
        ).items()).singleElement().extracting(
                value -> value.id()
        ).isEqualTo(result.interpretationId());
    }

    @Test
    void laterGenerationSupersedesPreviousInterpretationAndEmitsRevisionEvent() {
        PreparedJob first = prepareResponse("{\"store\":{}}");
        LlmAnalysisJob firstValidation = validationClaimStore.claimNext(
                "validation-worker", Duration.ofMinutes(2), NOW.plusSeconds(3)
        ).orElseThrow();
        validationTransitionStore.complete(
                firstValidation.id(), first.attemptId(), "validation-worker",
                LlmResponseValidationResult.valid("{\"store\":{}}"),
                NOW.plusSeconds(4)
        );
        LlmAnalysisJob firstPublication = publicationClaimStore.claimNext(
                "publication-worker", Duration.ofMinutes(2), NOW.plusSeconds(5)
        ).orElseThrow();
        LlmAnalysisAttempt firstAttempt = attemptStore.findSuccessfulByJobId(
                first.jobId()
        ).orElseThrow();
        LlmPublicationResult firstResult = publicationStore.publish(
                firstPublication.id(), firstAttempt.id(), "publication-worker",
                publicationMaterialFactory.create(firstAttempt), NOW.plusSeconds(6)
        );
        UUID snapshotId = jobStore.findById(first.jobId()).orElseThrow().snapshotId();
        LlmAnalysisJob secondQueued = jobStore.enqueue(
                request(snapshotId, 2, LlmAnalysisTriggerType.MANUAL_REGENERATION),
                NOW.plusSeconds(7)
        ).job();
        LlmAnalysisJob secondProvider = providerClaimStore.claimNext(
                "provider-worker", Duration.ofMinutes(2), NOW.plusSeconds(8)
        ).orElseThrow();
        LlmAnalysisAttempt secondAttempt = attemptStore.startProviderCall(
                secondProvider.id(), "provider-worker", LlmAnalysisAttemptType.INITIAL,
                "e".repeat(64), NOW.plusSeconds(8)
        );
        attemptStore.recordProviderResponse(
                secondAttempt.id(), "provider-worker",
                receipt("{\"store\":{\"headline\":{}}}", "test-request-revision"),
                NOW.plusSeconds(9)
        );
        providerTransitionStore.releaseForValidation(
                secondQueued.id(), "provider-worker", NOW.plusSeconds(9)
        );
        LlmAnalysisJob secondValidation = validationClaimStore.claimNext(
                "validation-worker", Duration.ofMinutes(2), NOW.plusSeconds(10)
        ).orElseThrow();
        validationTransitionStore.complete(
                secondValidation.id(), secondAttempt.id(), "validation-worker",
                LlmResponseValidationResult.valid(
                        "{\"store\":{\"headline\":{}}}"
                ),
                NOW.plusSeconds(11)
        );
        LlmAnalysisJob secondPublication = publicationClaimStore.claimNext(
                "publication-worker", Duration.ofMinutes(2), NOW.plusSeconds(12)
        ).orElseThrow();
        LlmAnalysisAttempt secondSucceeded = attemptStore.findSuccessfulByJobId(
                secondQueued.id()
        ).orElseThrow();

        LlmPublicationResult secondResult = publicationStore.publish(
                secondPublication.id(), secondSucceeded.id(), "publication-worker",
                publicationMaterialFactory.create(secondSucceeded), NOW.plusSeconds(13)
        );

        assertThat(secondResult.interpretationRevision()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT supersedes_interpretation_id FROM llm_interpretations "
                        + "WHERE id = ?",
                UUID.class,
                secondResult.interpretationId()
        )).isEqualTo(firstResult.interpretationId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT event_type FROM notification_events WHERE id = ?",
                String.class,
                secondResult.notificationEventId()
        )).isEqualTo("WEEKLY_REPORT_REVISED");
        UUID storeId = jdbcTemplate.queryForObject(
                "SELECT store_id FROM llm_interpretations WHERE id = ?",
                UUID.class,
                secondResult.interpretationId()
        );
        assertThat(interpretationQueryService.latest(storeId)
                .interpretation().id()).isEqualTo(secondResult.interpretationId());
        assertThat(interpretationQueryService.get(
                storeId, firstResult.interpretationId()
        ).interpretation().currentRevision()).isFalse();
        assertThat(interpretationQueryService.list(
                storeId, null, null, 0, 12
        ).items()).singleElement().extracting(
                value -> value.id()
        ).isEqualTo(secondResult.interpretationId());
    }

    @Test
    void publicationFailureRollsBackInterpretationEventAndJobTransition() {
        PreparedJob prepared = prepareResponse("{\"store\":{}}");
        LlmAnalysisJob validationJob = validationClaimStore.claimNext(
                "validation-worker", Duration.ofMinutes(2), NOW.plusSeconds(3)
        ).orElseThrow();
        validationTransitionStore.complete(
                validationJob.id(),
                prepared.attemptId(),
                "validation-worker",
                LlmResponseValidationResult.valid("{\"store\":{}}"),
                NOW.plusSeconds(4)
        );
        LlmAnalysisJob publicationJob = publicationClaimStore.claimNext(
                "publication-worker", Duration.ofMinutes(2), NOW.plusSeconds(5)
        ).orElseThrow();
        LlmAnalysisAttempt attempt = attemptStore.findSuccessfulByJobId(
                prepared.jobId()
        ).orElseThrow();
        WeeklyPublicationMaterial valid = publicationMaterialFactory.create(attempt);
        WeeklyPublicationMaterial invalidTimestamp = new WeeklyPublicationMaterial(
                valid.canonicalContent(),
                valid.contentHash(),
                NOW.plus(Duration.ofDays(1))
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> publicationStore.publish(
                publicationJob.id(),
                attempt.id(),
                "publication-worker",
                invalidTimestamp,
                NOW.plusSeconds(6)
        )).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM llm_interpretations WHERE analysis_job_id = ?",
                Integer.class,
                prepared.jobId()
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_events WHERE snapshot_id = "
                        + "(SELECT snapshot_id FROM llm_analysis_jobs WHERE id = ?)",
                Integer.class,
                prepared.jobId()
        )).isZero();
        assertThat(jobStore.findById(prepared.jobId()).orElseThrow().status())
                .isEqualTo(LlmAnalysisJobStatus.RUNNING);
    }

    @Test
    void successfulValidationClosesAttemptAndHandsOffToPublish() {
        PreparedJob prepared = prepareResponse("{\"store\":{}}");
        LlmAnalysisJob claimed = validationClaimStore.claimNext(
                "validation-worker", Duration.ofMinutes(2), NOW.plusSeconds(3)
        ).orElseThrow();

        LlmAnalysisJob result = validationTransitionStore.complete(
                claimed.id(),
                prepared.attemptId(),
                "validation-worker",
                LlmResponseValidationResult.valid("{\"store\":{\"headline\":{}}}"),
                NOW.plusSeconds(4)
        );

        assertThat(result.status()).isEqualTo(LlmAnalysisJobStatus.WAITING_RETRY);
        assertThat(result.phase()).isEqualTo(LlmAnalysisPhase.PUBLISH);
        assertThat(result.leaseOwner()).isNull();
        LlmAnalysisAttempt attempt = attemptStore.findById(prepared.attemptId())
                .orElseThrow();
        assertThat(attempt.status()).isEqualTo(LlmAnalysisAttemptStatus.SUCCEEDED);
        assertThat(attempt.validationViolations()).isEqualTo("[]");
        assertThat(attempt.responseBody()).isEqualTo("{\"store\":{}}");
        assertThat(attempt.validatedResponseBody())
                .isEqualTo("{\"store\":{\"headline\":{}}}");
        assertThat(attempt.validatedResponseHash())
                .hasSize(64)
                .isNotEqualTo(attempt.responseHash());
        assertThat(validationClaimStore.claimNext(
                "second-validation-worker",
                Duration.ofMinutes(2),
                NOW.plusSeconds(5)
        )).isEmpty();
        lifecycleStore.requestCancellation(result.id(), NOW.plusSeconds(6));
    }

    @Test
    void oneInvalidResponseRetriesAndSecondFailureBecomesTerminal() {
        PreparedJob prepared = prepareResponse("{\"store\":{}}");
        LlmAnalysisJob claimed = validationClaimStore.claimNext(
                "validation-worker", Duration.ofMinutes(2), NOW.plusSeconds(3)
        ).orElseThrow();
        LlmResponseValidationResult invalid = LlmResponseValidationResult.invalid(
                LlmValidationOutcome.SEMANTIC_INVALID,
                List.of(new LlmValidationViolation(
                        "UNKNOWN_EVIDENCE_REF",
                        "$.store.headline.evidenceRefs[0]",
                        "UNKNOWN.EVIDENCE"
                ))
        );

        LlmAnalysisJob retryScheduled = validationTransitionStore.complete(
                claimed.id(),
                prepared.attemptId(),
                "validation-worker",
                invalid,
                NOW.plusSeconds(4)
        );

        assertThat(retryScheduled.status())
                .isEqualTo(LlmAnalysisJobStatus.WAITING_RETRY);
        assertThat(retryScheduled.phase())
                .isEqualTo(LlmAnalysisPhase.VALIDATE_RESPONSE);
        assertThat(retryScheduled.validationRetryCount()).isOne();
        assertThat(retryPromptFactory.appendRetryInstruction(
                "base prompt",
                retryScheduled
        ))
                .contains(
                        "UNKNOWN_EVIDENCE_REF",
                        "SERVICE_SALES",
                        "CATEGORY:SERVICE",
                        "do not add angle brackets",
                        "COMPETENCY_LEADER"
                )
                .doesNotContain("UNKNOWN.EVIDENCE");
        LlmAnalysisAttempt firstAttempt = attemptStore.findById(prepared.attemptId())
                .orElseThrow();
        assertThat(firstAttempt.status())
                .isEqualTo(LlmAnalysisAttemptStatus.SEMANTIC_INVALID);
        assertThat(firstAttempt.validationViolations())
                .contains("UNKNOWN_EVIDENCE_REF", "UNKNOWN.EVIDENCE")
                .doesNotContain("store\":{}");

        LlmAnalysisJob providerRetry = providerClaimStore.claimNext(
                "retry-provider", Duration.ofMinutes(2), NOW.plusSeconds(5)
        ).orElseThrow();
        assertThat(providerRetry.phase()).isEqualTo(LlmAnalysisPhase.VALIDATE_RESPONSE);
        LlmAnalysisAttempt retryAttempt = attemptStore.startProviderCall(
                providerRetry.id(),
                "retry-provider",
                LlmAnalysisAttemptType.VALIDATION_RETRY,
                "d".repeat(64),
                NOW.plusSeconds(5)
        );
        attemptStore.recordProviderResponse(
                retryAttempt.id(),
                "retry-provider",
                receipt("{\"store\":{}}", "test-request-2"),
                NOW.plusSeconds(6)
        );
        providerTransitionStore.releaseForValidation(
                providerRetry.id(),
                "retry-provider",
                NOW.plusSeconds(6)
        );
        LlmAnalysisJob validationRetry = validationClaimStore.claimNext(
                "validation-worker", Duration.ofMinutes(2), NOW.plusSeconds(7)
        ).orElseThrow();

        LlmAnalysisJob terminal = validationTransitionStore.complete(
                validationRetry.id(),
                retryAttempt.id(),
                "validation-worker",
                invalid,
                NOW.plusSeconds(8)
        );

        assertThat(terminal.status())
                .isEqualTo(LlmAnalysisJobStatus.VALIDATION_FAILED);
        assertThat(terminal.terminalReasonCode())
                .isEqualTo("LLM_RESPONSE_SEMANTIC_INVALID");
        assertThat(terminal.finishedAt()).isEqualTo(NOW.plusSeconds(8));
        assertThat(attemptStore.findById(retryAttempt.id()).orElseThrow().status())
                .isEqualTo(LlmAnalysisAttemptStatus.SEMANTIC_INVALID);
    }

    @Test
    void expiredPublicationLeaseRequeuesSameSuccessfulAttempt() {
        PreparedJob prepared = prepareResponse("{\"store\":{}}");
        LlmAnalysisJob validationJob = validationClaimStore.claimNext(
                "validation-worker", Duration.ofMinutes(2), NOW.plusSeconds(3)
        ).orElseThrow();
        validationTransitionStore.complete(
                validationJob.id(), prepared.attemptId(), "validation-worker",
                LlmResponseValidationResult.valid("{\"store\":{}}"),
                NOW.plusSeconds(4)
        );
        publicationClaimStore.claimNext(
                "crashed-publication-worker",
                Duration.ofSeconds(5),
                NOW.plusSeconds(5)
        ).orElseThrow();

        LlmAnalysisJob recovered = lifecycleStore.recoverOneExpiredLease(
                NOW.plusSeconds(20),
                NOW.plusSeconds(11)
        ).orElseThrow();

        assertThat(recovered.status()).isEqualTo(LlmAnalysisJobStatus.WAITING_RETRY);
        assertThat(recovered.phase()).isEqualTo(LlmAnalysisPhase.PUBLISH);
        assertThat(attemptStore.findSuccessfulByJobId(prepared.jobId())).isPresent();
        assertThat(publicationClaimStore.claimNext(
                "replacement-publication-worker",
                Duration.ofMinutes(2),
                NOW.plusSeconds(21)
        )).isPresent();
    }

    @Test
    void expiredValidationLeaseRequeuesThePersistedResponseWithoutProviderCall() {
        PreparedJob prepared = prepareResponse("{\"store\":{}}");
        LlmAnalysisJob claimed = validationClaimStore.claimNext(
                "crashed-validation-worker",
                Duration.ofSeconds(5),
                NOW.plusSeconds(3)
        ).orElseThrow();

        LlmAnalysisJob recovered = lifecycleStore.recoverOneExpiredLease(
                NOW.plusSeconds(15),
                NOW.plusSeconds(9)
        ).orElseThrow();

        assertThat(claimed.status()).isEqualTo(LlmAnalysisJobStatus.RUNNING);
        assertThat(recovered.status()).isEqualTo(LlmAnalysisJobStatus.WAITING_RETRY);
        assertThat(recovered.phase()).isEqualTo(LlmAnalysisPhase.VALIDATE_RESPONSE);
        assertThat(attemptStore.findOpenByJobId(prepared.jobId()))
                .get()
                .extracting(LlmAnalysisAttempt::status)
                .isEqualTo(LlmAnalysisAttemptStatus.RESPONSE_RECEIVED);
        assertThat(validationClaimStore.claimNext(
                "replacement-validation-worker",
                Duration.ofMinutes(2),
                NOW.plusSeconds(16)
        )).isPresent();
    }

    private PreparedJob prepareResponse(String responseBody) {
        UUID snapshotId = createSnapshot();
        LlmAnalysisJob queued = jobStore.enqueue(request(snapshotId), NOW).job();
        LlmAnalysisJob providerJob = providerClaimStore.claimNext(
                "provider-worker", Duration.ofMinutes(2), NOW.plusSeconds(1)
        ).orElseThrow();
        LlmAnalysisAttempt attempt = attemptStore.startProviderCall(
                providerJob.id(),
                "provider-worker",
                LlmAnalysisAttemptType.INITIAL,
                "c".repeat(64),
                NOW.plusSeconds(1)
        );
        attemptStore.recordProviderResponse(
                attempt.id(),
                "provider-worker",
                receipt(responseBody, "test-request-1"),
                NOW.plusSeconds(2)
        );
        providerTransitionStore.releaseForValidation(
                queued.id(),
                "provider-worker",
                NOW.plusSeconds(2)
        );
        return new PreparedJob(queued.id(), attempt.id());
    }

    private LlmProviderResponseReceipt receipt(String body, String requestId) {
        return new LlmProviderResponseReceipt(
                body,
                "test-model-v1",
                requestId,
                100,
                40,
                0,
                null,
                140,
                new BigDecimal("0.50"),
                "RUB",
                250L,
                200
        );
    }

    private LlmAnalysisJobRequest request(UUID snapshotId) {
        return request(snapshotId, 1, LlmAnalysisTriggerType.INITIAL);
    }

    private LlmAnalysisJobRequest request(
            UUID snapshotId,
            int generationRevision,
            LlmAnalysisTriggerType triggerType
    ) {
        return new LlmAnalysisJobRequest(
                snapshotId,
                generationRevision,
                triggerType,
                null,
                "TEST",
                "test-model",
                "test-provider-v1",
                1,
                "weekly-interpretation-v1",
                "weekly-analysis-v1",
                "weekly-budget-v1",
                "{\"maxOutputTokens\":1000,\"maxProviderCalls\":2,\"temperature\":0.2}",
                "a".repeat(64),
                1,
                1,
                NOW.plus(Duration.ofMinutes(5))
        );
    }

    private UUID createSnapshot() {
        UUID connectionId = jdbcTemplate.queryForObject(
                "SELECT id FROM integration_connections WHERE connection_key = ?",
                UUID.class,
                "livesklad-default"
        );
        UUID storeId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO stores (
                    id, connection_id, source_system, external_id, name, timezone
                ) VALUES (?, ?, 'LIVESKLAD', ?, 'Validation pipeline',
                          'Europe/Kaliningrad')
                """,
                storeId,
                connectionId,
                "validation-pipeline-" + storeId
        );
        UUID syncJobId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO sync_jobs (
                    id, connection_id, job_type, status, phase, period_start, period_end,
                    cursor_start, current_window_end, window_size_minutes, max_attempts,
                    next_attempt_at, started_at, finished_at
                ) VALUES (
                    ?, ?, 'INCREMENTAL', 'SUCCESS', 'RETURNS', ?, ?, ?, ?, 1440, 5,
                    ?, ?, ?
                )
                """,
                syncJobId,
                connectionId,
                Timestamp.from(NOW.minus(Duration.ofDays(7))),
                Timestamp.from(NOW),
                Timestamp.from(NOW),
                Timestamp.from(NOW),
                Timestamp.from(NOW),
                Timestamp.from(NOW.minusSeconds(60)),
                Timestamp.from(NOW)
        );
        UUID snapshotId = UUID.randomUUID();
        LocalDate start = LocalDate.of(2026, 7, 27);
        jdbcTemplate.update(
                """
                INSERT INTO analytics_snapshots (
                    id, store_id, period_start, period_end, timezone, revision,
                    revision_reason_code, source_sync_job_id, source_sync_completed_at,
                    source_data_cutoff, facts_schema_version, metrics_contract_version,
                    calculation_version, quality_policy_version, quality_status,
                    facts_payload, facts_hash, created_at
                ) VALUES (
                    ?, ?, ?, ?, 'Europe/Kaliningrad', 1, 'INITIAL', ?, ?, ?, 1,
                    'weekly-metrics-v1', 'weekly-snapshot-v1', 'weekly-quality-v1',
                    'READY',
                    jsonb_build_object(
                        'manifest', jsonb_build_object(
                            'competencyCodes', jsonb_build_array('SERVICE_SALES'),
                            'categoryCodes', jsonb_build_array('SERVICE')
                        )
                    ),
                    ?, ?
                )
                """,
                snapshotId,
                storeId,
                start,
                start.plusDays(6),
                syncJobId,
                Timestamp.from(NOW),
                Timestamp.from(NOW),
                "b".repeat(64),
                Timestamp.from(NOW)
        );
        UUID employeeId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO employees (
                    id, connection_id, source_system, external_id, full_name
                ) VALUES (?, ?, 'LIVESKLAD', ?, 'Текущее имя')
                """,
                employeeId,
                connectionId,
                "interpretation-employee-" + employeeId
        );
        jdbcTemplate.update(
                """
                INSERT INTO analytics_snapshot_employees (
                    snapshot_id, employee_id, employee_ref, display_name_snapshot
                ) VALUES (?, ?, 'E01', 'Историческое имя')
                """,
                snapshotId,
                employeeId
        );
        return snapshotId;
    }

    private record PreparedJob(UUID jobId, UUID attemptId) {
    }
}
