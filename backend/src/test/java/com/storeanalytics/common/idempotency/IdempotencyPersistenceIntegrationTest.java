package com.storeanalytics.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.auth.repository.AppUserRepository;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
        "app.reports.backfill.worker-enabled=false",
        "app.reports.annual-scheduling-enabled=false",
        "app.sync.worker-enabled=false",
        "app.sync.schedule-enabled=false",
        "app.maintenance.retention.scheduling-enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IdempotencyPersistenceIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private IdempotencyService service;

    @Autowired
    private IdempotencyReceiptRepository receiptRepository;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private TransactionTemplate transactions;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void persistsReplayAndRejectsChangedRequestIdentity() {
        UUID actorId = actor("idempotency-replay@example.test").getId();
        long receiptCountBefore = receiptRepository.count();
        AtomicInteger executions = new AtomicInteger();
        IdempotencyRequest request = request(7);

        StoredResult original = execute(
                actorId,
                "integration-replay-1234",
                request,
                () -> new StoredResult("approved", executions.incrementAndGet())
        );
        StoredResult replay = execute(
                actorId,
                "integration-replay-1234",
                request,
                () -> new StoredResult("unexpected", executions.incrementAndGet())
        );

        assertThat(replay).isEqualTo(original);
        assertThat(executions).hasValue(1);
        assertThat(receiptRepository.count()).isEqualTo(receiptCountBefore + 1);
        assertThatThrownBy(() -> execute(
                actorId,
                "integration-replay-1234",
                request(8),
                () -> new StoredResult("unexpected", executions.incrementAndGet())
        )).isInstanceOf(IdempotencyKeyConflictException.class);
        assertThat(executions).hasValue(1);
    }

    @Test
    void serializesConcurrentRetriesAndRunsSideEffectOnce() throws Exception {
        UUID actorId = actor("idempotency-concurrent@example.test").getId();
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<StoredResult> first = executor.submit(() -> concurrentExecute(
                    actorId, executions, ready, start
            ));
            Future<StoredResult> second = executor.submit(() -> concurrentExecute(
                    actorId, executions, ready, start
            ));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            StoredResult firstResult = first.get(10, TimeUnit.SECONDS);
            StoredResult secondResult = second.get(10, TimeUnit.SECONDS);
            assertThat(firstResult).isEqualTo(secondResult);
            assertThat(executions).hasValue(1);
        }
    }

    private StoredResult concurrentExecute(
            UUID actorId,
            AtomicInteger executions,
            CountDownLatch ready,
            CountDownLatch start
    ) throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return execute(
                actorId,
                "integration-concurrent-1234",
                request(11),
                () -> new StoredResult("paid", executions.incrementAndGet())
        );
    }

    private StoredResult execute(
            UUID actorId,
            String key,
            IdempotencyRequest request,
            java.util.function.Supplier<StoredResult> command
    ) {
        return transactions.execute(status -> service.execute(
                actorId,
                key,
                request,
                StoredResult.class,
                command
        ));
    }

    private AppUser actor(String email) {
        return userRepository.saveAndFlush(new AppUser(
                email,
                "test-password-hash",
                "Idempotency Test Administrator",
                UserRole.ADMIN
        ));
    }

    private IdempotencyRequest request(long version) {
        return new IdempotencyRequest(
                "PAYROLL_APPROVE",
                "store/integration/payroll-run/test",
                Map.of("version", version)
        );
    }

    public record StoredResult(String status, int sequence) {
    }
}
