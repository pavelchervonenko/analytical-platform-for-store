package com.storeanalytics.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import tools.jackson.databind.ObjectMapper;
import com.storeanalytics.common.exception.InvalidRequestException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class IdempotencyServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-27T00:00:00Z");

    private IdempotencyReceiptRepository repository;
    private AtomicReference<IdempotencyReceipt> stored;
    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        repository = mock(IdempotencyReceiptRepository.class);
        stored = new AtomicReference<>();
        when(repository.findByActorIdAndIdempotencyKey(any(), any()))
                .thenAnswer(ignored -> Optional.ofNullable(stored.get()));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            IdempotencyReceipt receipt = invocation.getArgument(0);
            stored.set(receipt);
            return receipt;
        });
        service = new IdempotencyService(
                repository,
                new IdempotencyProperties(Duration.ofHours(24), 1000),
                mock(JdbcTemplate.class),
                new ObjectMapper().rebuild().findAndAddModules().build(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void storesAndReplaysExactSuccessfulResultWithoutExecutingAgain() {
        UUID actorId = UUID.randomUUID();
        AtomicInteger executions = new AtomicInteger();
        IdempotencyRequest request = new IdempotencyRequest(
                "PAYROLL_APPROVE",
                "store/one/payroll-run/two",
                Map.of("version", 4)
        );

        TestResponse first = service.execute(
                actorId,
                "approval-request-1234",
                request,
                TestResponse.class,
                () -> new TestResponse("approved", executions.incrementAndGet())
        );
        TestResponse replay = service.execute(
                actorId,
                "approval-request-1234",
                request,
                TestResponse.class,
                () -> new TestResponse("unexpected", executions.incrementAndGet())
        );

        assertThat(first).isEqualTo(new TestResponse("approved", 1));
        assertThat(replay).isEqualTo(first);
        assertThat(executions).hasValue(1);
        verify(repository, times(1)).saveAndFlush(any());
    }

    @Test
    void rejectsKeyReuseWhenCanonicalRequestBodyChanges() {
        UUID actorId = UUID.randomUUID();
        AtomicInteger executions = new AtomicInteger();
        IdempotencyRequest original = new IdempotencyRequest(
                "PAYROLL_MARK_PAID",
                "store/one/payroll-run/two",
                Map.of("version", 4)
        );
        service.execute(
                actorId,
                "paid-request-1234",
                original,
                TestResponse.class,
                () -> new TestResponse("paid", executions.incrementAndGet())
        );

        assertThatThrownBy(() -> service.execute(
                actorId,
                "paid-request-1234",
                new IdempotencyRequest(
                        "PAYROLL_MARK_PAID",
                        "store/one/payroll-run/two",
                        Map.of("version", 5)
                ),
                TestResponse.class,
                () -> new TestResponse("unexpected", executions.incrementAndGet())
        )).isInstanceOf(IdempotencyKeyConflictException.class);
        assertThat(executions).hasValue(1);
    }

    @Test
    void validatesOpaqueKeyBeforeCommandExecution() {
        AtomicInteger executions = new AtomicInteger();

        assertThatThrownBy(() -> service.execute(
                UUID.randomUUID(),
                "short",
                new IdempotencyRequest("PAYROLL_APPROVE", "payroll/run", Map.of()),
                TestResponse.class,
                () -> new TestResponse("unexpected", executions.incrementAndGet())
        )).isInstanceOf(InvalidRequestException.class);
        assertThat(executions).hasValue(0);
        assertThat(stored).hasValue(null);
    }

    private record TestResponse(String status, int sequence) {
    }
}
