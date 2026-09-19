package com.storeanalytics.performance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.common.exception.PreconditionFailedException;
import com.storeanalytics.employee.model.Employee;
import com.storeanalytics.employee.model.EmployeeStoreAssignment;
import com.storeanalytics.performance.model.StorePlanTargets;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.model.StoreSchedule;
import com.storeanalytics.store.repository.StoreRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
class OptimisticConcurrencyIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private StorePerformancePlanService planService;

    @Autowired
    private WorkScheduleService scheduleService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private StoreRepository storeRepository;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void rejectsSecondPlanSaveFromSameBrowserVersion() {
        Fixture fixture = fixture("plan");
        YearMonth month = YearMonth.of(2026, 7);
        StorePerformancePlanView original = planService.upsert(
                fixture.storeId(),
                month,
                targets("1000000.00"),
                null,
                "*",
                fixture.actorId()
        );
        String originalEtag = StorePerformancePlanService.etag(original);

        StorePerformancePlanView firstSave = planService.upsert(
                fixture.storeId(),
                month,
                targets("1100000.00"),
                originalEtag,
                null,
                fixture.actorId()
        );

        Object staleResult;
        try {
            staleResult = planService.upsert(
                    fixture.storeId(),
                    month,
                    targets("1200000.00"),
                    originalEtag,
                    null,
                    fixture.actorId()
            );
        } catch (RuntimeException exception) {
            staleResult = exception;
        }

        assertThat(staleResult).isInstanceOf(PreconditionFailedException.class);
        assertThat(planService.get(fixture.storeId(), month).revenueTarget())
                .isEqualByComparingTo(firstSave.revenueTarget());
    }

    @Test
    void concurrentScheduleReplacementsWithSameEtagCommitOnlyOnce() throws Exception {
        Fixture fixture = fixture("schedule");
        LocalDate date = LocalDate.of(2026, 7, 21);
        WorkScheduleDayView original = scheduleService.getDay(fixture.storeId(), date);
        String originalEtag = WorkScheduleService.etag(
                original.storeId(), original.workDate(), original.revision()
        );
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Object> first = executor.submit(() -> replaceConcurrently(
                    fixture, date, originalEtag, ready, start
            ));
            Future<Object> second = executor.submit(() -> replaceConcurrently(
                    fixture, date, originalEtag, ready, start
            ));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Object> outcomes = List.of(
                    outcome(first),
                    outcome(second)
            );
            assertThat(outcomes).filteredOn(WorkScheduleDayView.class::isInstance)
                    .hasSize(1);
            assertThat(outcomes).filteredOn(PreconditionFailedException.class::isInstance)
                    .hasSize(1);
        }

        assertThat(scheduleService.getDay(fixture.storeId(), date).revision()).isOne();
    }

    @Test
    void sameEmployeeCanHaveIndependentShiftsInTwoStoresOnSameDate() {
        SharedEmployeeFixture fixture = sharedEmployeeFixture();
        LocalDate date = LocalDate.of(2026, 9, 19);

        WorkScheduleDayView firstStoreDay = scheduleService.replaceDay(
                fixture.firstStoreId(),
                date,
                List.of(new WorkShiftInput(fixture.employeeId(), new BigDecimal("11.00"))),
                WorkScheduleService.etag(fixture.firstStoreId(), date, 0),
                fixture.actorId()
        );
        WorkScheduleDayView secondStoreDay = scheduleService.replaceDay(
                fixture.secondStoreId(),
                date,
                List.of(new WorkShiftInput(fixture.employeeId(), new BigDecimal("7.50"))),
                WorkScheduleService.etag(fixture.secondStoreId(), date, 0),
                fixture.actorId()
        );

        assertThat(firstStoreDay.shifts()).singleElement().satisfies(shift -> {
            assertThat(shift.employeeId()).isEqualTo(fixture.employeeId());
            assertThat(shift.workedHours()).isEqualByComparingTo("11.00");
        });
        assertThat(secondStoreDay.shifts()).singleElement().satisfies(shift -> {
            assertThat(shift.employeeId()).isEqualTo(fixture.employeeId());
            assertThat(shift.workedHours()).isEqualByComparingTo("7.50");
        });
        assertThat(scheduleService.getDay(fixture.firstStoreId(), date).shifts())
                .extracting(EmployeeShiftView::workedHours)
                .containsExactly(new BigDecimal("11.00"));
        assertThat(scheduleService.getDay(fixture.secondStoreId(), date).shifts())
                .extracting(EmployeeShiftView::workedHours)
                .containsExactly(new BigDecimal("7.50"));
    }

    @Test
    void dayReadWaitsForInFlightStoreWriteAndReturnsItsCommittedVersion() throws Exception {
        SharedEmployeeFixture fixture = sharedEmployeeFixture();
        LocalDate date = LocalDate.of(2026, 9, 20);
        scheduleService.replaceDay(
                fixture.firstStoreId(),
                date,
                List.of(new WorkShiftInput(fixture.employeeId(), new BigDecimal("11.00"))),
                WorkScheduleService.etag(fixture.firstStoreId(), date, 0),
                fixture.actorId()
        );
        CountDownLatch writerReady = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Void> writer = executor.submit(() -> {
                transactions.executeWithoutResult(status -> {
                    storeRepository.findByIdForUpdate(fixture.firstStoreId()).orElseThrow();
                    entityManager.createNativeQuery("""
                            UPDATE employee_work_shifts
                            SET worked_hours = 7.50
                            WHERE store_id = :storeId
                              AND employee_id = :employeeId
                              AND work_date = :workDate
                            """)
                            .setParameter("storeId", fixture.firstStoreId())
                            .setParameter("employeeId", fixture.employeeId())
                            .setParameter("workDate", date)
                            .executeUpdate();
                    entityManager.createNativeQuery("""
                            UPDATE work_schedule_day_revisions
                            SET revision = revision + 1
                            WHERE store_id = :storeId AND work_date = :workDate
                            """)
                            .setParameter("storeId", fixture.firstStoreId())
                            .setParameter("workDate", date)
                            .executeUpdate();
                    writerReady.countDown();
                    await(releaseWriter);
                });
                return null;
            });
            assertThat(writerReady.await(5, TimeUnit.SECONDS)).isTrue();

            Future<WorkScheduleDayView> reader = executor.submit(
                    () -> scheduleService.getDay(fixture.firstStoreId(), date)
            );
            assertThatThrownBy(() -> reader.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseWriter.countDown();
            writer.get(10, TimeUnit.SECONDS);
            WorkScheduleDayView day = reader.get(10, TimeUnit.SECONDS);
            assertThat(day.revision()).isEqualTo(2);
            assertThat(day.shifts()).singleElement().satisfies(shift ->
                    assertThat(shift.workedHours()).isEqualByComparingTo("7.50")
            );
        } finally {
            releaseWriter.countDown();
        }
    }

    private Object replaceConcurrently(
            Fixture fixture,
            LocalDate date,
            String etag,
            CountDownLatch ready,
            CountDownLatch start
    ) throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return scheduleService.replaceDay(
                fixture.storeId(),
                date,
                List.of(),
                etag,
                fixture.actorId()
        );
    }

    private Object outcome(Future<Object> future) throws Exception {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            return exception.getCause();
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test coordination");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating test", exception);
        }
    }

    private Fixture fixture(String suffix) {
        return transactions.execute(status -> {
            Store store = Store.manual(
                    "optimistic-" + suffix + "-" + UUID.randomUUID(),
                    "Optimistic concurrency store",
                    null,
                    new StoreSchedule(
                            "Europe/Moscow",
                            LocalTime.MIDNIGHT,
                            LocalTime.of(10, 0),
                            LocalTime.of(21, 0)
                    )
            );
            AppUser actor = new AppUser(
                    "optimistic-" + suffix + "-" + UUID.randomUUID() + "@example.test",
                    "test-password-hash",
                    "Optimistic Concurrency Administrator",
                    UserRole.ADMIN
            );
            entityManager.persist(store);
            entityManager.persist(actor);
            entityManager.flush();
            return new Fixture(store.getId(), actor.getId());
        });
    }

    private SharedEmployeeFixture sharedEmployeeFixture() {
        return transactions.execute(status -> {
            String suffix = UUID.randomUUID().toString();
            Store firstStore = Store.manual(
                    "shared-first-" + suffix,
                    "Shared employee first store",
                    null,
                    schedule()
            );
            Store secondStore = Store.manual(
                    "shared-second-" + suffix,
                    "Shared employee second store",
                    null,
                    schedule()
            );
            Employee employee = Employee.manual(
                    "shared-employee-" + suffix,
                    "Shared Employee"
            );
            AppUser actor = new AppUser(
                    "shared-employee-" + suffix + "@example.test",
                    "test-password-hash",
                    "Shared Employee Administrator",
                    UserRole.ADMIN
            );
            entityManager.persist(firstStore);
            entityManager.persist(secondStore);
            entityManager.persist(employee);
            entityManager.persist(actor);
            entityManager.flush();
            entityManager.persist(new EmployeeStoreAssignment(employee, firstStore, true));
            entityManager.persist(new EmployeeStoreAssignment(employee, secondStore, true));
            entityManager.flush();
            return new SharedEmployeeFixture(
                    firstStore.getId(), secondStore.getId(), employee.getId(), actor.getId()
            );
        });
    }

    private StoreSchedule schedule() {
        return new StoreSchedule(
                "Europe/Moscow",
                LocalTime.MIDNIGHT,
                LocalTime.of(10, 0),
                LocalTime.of(21, 0)
        );
    }

    private StorePlanTargets targets(String revenue) {
        return new StorePlanTargets(
                new BigDecimal(revenue),
                new BigDecimal("4.00"),
                new BigDecimal("3.00"),
                new BigDecimal("7.00")
        );
    }

    private record Fixture(UUID storeId, UUID actorId) {
    }

    private record SharedEmployeeFixture(
            UUID firstStoreId,
            UUID secondStoreId,
            UUID employeeId,
            UUID actorId
    ) {
    }
}
