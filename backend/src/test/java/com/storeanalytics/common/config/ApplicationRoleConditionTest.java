package com.storeanalytics.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class ApplicationRoleConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RoleTestConfiguration.class);

    @Test
    void combinedRoleIsTheSafeLocalDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(WorkerOwnedBean.class);
            assertThat(context.getBean(ApplicationRuntimeProperties.class).role())
                    .isEqualTo(ApplicationRole.COMBINED);
        });
    }

    @Test
    void apiRoleDoesNotCreateWorkerOwnedBeans() {
        contextRunner
                .withPropertyValues("app.runtime.role=API")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(WorkerOwnedBean.class);
                    assertThat(context.getBean(ApplicationRuntimeProperties.class).role())
                            .isEqualTo(ApplicationRole.API);
                });
    }

    @Test
    void workerRoleCreatesWorkerOwnedBeans() {
        contextRunner
                .withPropertyValues("app.runtime.role=worker")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(WorkerOwnedBean.class);
                    assertThat(context.getBean(ApplicationRuntimeProperties.class).role())
                            .isEqualTo(ApplicationRole.WORKER);
                });
    }

    @Test
    void unknownRoleStopsApplicationStartup() {
        contextRunner
                .withPropertyValues("app.runtime.role=UNKNOWN_ROLE")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ApplicationRuntimeProperties.class)
    static class RoleTestConfiguration {

        @Bean
        @ConditionalOnApplicationRole({
                ApplicationRole.WORKER,
                ApplicationRole.COMBINED
        })
        WorkerOwnedBean workerOwnedBean() {
            return new WorkerOwnedBean();
        }
    }

    static final class WorkerOwnedBean {
    }
}
