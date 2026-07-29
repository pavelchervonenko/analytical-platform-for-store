package com.storeanalytics.auth.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.audit.repository.AuditLogRepository;
import com.storeanalytics.audit.service.AuditAction;
import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.auth.repository.AppUserRepository;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class BootstrapAdminIntegrationTest {

    private static final String PASSWORD = "correct horse battery staple";

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private BootstrapAdminService bootstrapAdminService;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void concurrentReplicasCreateExactlyOnePasswordChangeRequiredAdmin()
            throws Exception {
        BootstrapAdminProperties properties = new BootstrapAdminProperties(
                "bootstrap@example.com", PASSWORD, "Bootstrap Administrator"
        );
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<BootstrapAdminOutcome> first = createConcurrently(
                    executor, barrier, properties
            );
            CompletableFuture<BootstrapAdminOutcome> second = createConcurrently(
                    executor, barrier, properties
            );
            List<BootstrapAdminOutcome.Status> statuses = List.of(
                    first.get(30, TimeUnit.SECONDS).status(),
                    second.get(30, TimeUnit.SECONDS).status()
            );

            assertThat(statuses).containsExactlyInAnyOrder(
                    BootstrapAdminOutcome.Status.CREATED,
                    BootstrapAdminOutcome.Status.USERS_EXIST
            );
        } finally {
            executor.shutdownNow();
        }

        assertThat(userRepository.count()).isOne();
        AppUser administrator = userRepository.findAll().getFirst();
        assertThat(administrator.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(administrator.isPasswordChangeRequired()).isTrue();
        assertThat(passwordEncoder.matches(
                PASSWORD, administrator.getPasswordHash()
        )).isTrue();
        assertThat(auditLogRepository.findAll())
                .singleElement()
                .satisfies(audit -> {
                    assertThat(audit.getAction()).isEqualTo(
                            AuditAction.BOOTSTRAP_ADMIN_CREATED.name()
                    );
                    assertThat(audit.getActorUser()).isNull();
                    assertThat(audit.getEntityId()).isEqualTo(
                            administrator.getId().toString()
                    );
                });
    }

    private CompletableFuture<BootstrapAdminOutcome> createConcurrently(
            ExecutorService executor,
            CyclicBarrier barrier,
            BootstrapAdminProperties properties
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                barrier.await(10, TimeUnit.SECONDS);
                return bootstrapAdminService.createIfDatabaseIsEmpty(properties);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }, executor);
    }
}
