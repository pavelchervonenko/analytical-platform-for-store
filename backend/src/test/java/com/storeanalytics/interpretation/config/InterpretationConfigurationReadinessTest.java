package com.storeanalytics.interpretation.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.common.config.ApplicationRuntimeProperties;
import com.storeanalytics.integration.llm.yandex.YandexLlmProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class InterpretationConfigurationReadinessTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void integrationsAreDisabledAndRequireNoSecretsByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(InterpretationFeatureProperties.class))
                    .extracting(
                            InterpretationFeatureProperties::snapshotEnabled,
                            InterpretationFeatureProperties::generationEnabled,
                            InterpretationFeatureProperties::publicationEnabled
                    )
                    .containsExactly(false, false, false);
            assertThat(context.getBean(WeeklySnapshotWorkerProperties.class).enabled())
                    .isFalse();
            assertThat(context.getBean(WeeklySnapshotPlannerProperties.class).enabled())
                    .isFalse();
            assertThat(context.getBean(LlmAnalysisPlannerProperties.class).enabled())
                    .isFalse();
            assertThat(context.getBean(LlmAnalysisWorkerProperties.class).enabled())
                    .isFalse();
            assertThat(context.getBean(YandexLlmProperties.class).isConfigured()).isFalse();
        });
    }

    @Test
    void workerFailsFastWhenGenerationIsEnabledWithoutCredentials() {
        contextRunner
                .withPropertyValues(
                        "app.runtime.role=WORKER",
                        "app.interpretation.generation-enabled=true"
                )
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void apiDoesNotRequireWorkerCredentials() {
        contextRunner
                .withPropertyValues(
                        "app.runtime.role=API",
                        "app.interpretation.generation-enabled=true"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .doesNotHaveBean(InterpretationConfigurationReadiness.class);
                });
    }

    @Test
    void workerStartsWithExplicitVersionedConfiguration() {
        contextRunner
                .withPropertyValues(
                        "app.runtime.role=WORKER",
                        "app.interpretation.generation-enabled=true",
                        "app.llm.yandex.folder-id=folder",
                        "app.llm.yandex.api-key=secret-value",
                        "app.llm.yandex.model-uri=gpt://folder/yandexgpt-5.1"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .hasSingleBean(InterpretationConfigurationReadiness.class);
                    assertThat(context.getBean(YandexLlmProperties.class).toString())
                            .contains("REDACTED")
                            .doesNotContain("secret-value");
                });
    }

    @Test
    void workerStartsWithPackagedNextVersionPair() {
        contextRunner
                .withPropertyValues(
                        "app.runtime.role=WORKER",
                        "app.interpretation.generation-enabled=true",
                        "app.llm.prompt-version=weekly-interpretation-v4",
                        "app.llm.content-schema-version=2",
                        "app.llm.yandex.folder-id=folder",
                        "app.llm.yandex.api-key=secret-value",
                        "app.llm.yandex.model-uri=gpt://folder/yandexgpt-5.1"
                )
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void workerStartsWithPackagedStrictConcisePromptPair() {
        contextRunner
                .withPropertyValues(
                        "app.runtime.role=WORKER",
                        "app.interpretation.generation-enabled=true",
                        "app.llm.prompt-version=weekly-interpretation-v7",
                        "app.llm.content-schema-version=2",
                        "app.llm.yandex.folder-id=folder",
                        "app.llm.yandex.api-key=secret-value",
                        "app.llm.yandex.model-uri=gpt://folder/yandexgpt-5.1"
                )
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void workerStartsWithPackagedActionableConcisePromptPair() {
        contextRunner
                .withPropertyValues(
                        "app.runtime.role=WORKER",
                        "app.interpretation.generation-enabled=true",
                        "app.llm.prompt-version=weekly-interpretation-v8",
                        "app.llm.content-schema-version=2",
                        "app.llm.yandex.folder-id=folder",
                        "app.llm.yandex.api-key=secret-value",
                        "app.llm.yandex.model-uri=gpt://folder/yandexgpt-5.1"
                )
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void workerStartsWithPackagedEvidenceGuardedPromptPair() {
        contextRunner
                .withPropertyValues(
                        "app.runtime.role=WORKER",
                        "app.interpretation.generation-enabled=true",
                        "app.llm.prompt-version=weekly-interpretation-v9",
                        "app.llm.content-schema-version=2",
                        "app.llm.yandex.folder-id=folder",
                        "app.llm.yandex.api-key=secret-value",
                        "app.llm.yandex.model-uri=gpt://folder/yandexgpt-5.1"
                )
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void workerStartsWithPackagedHardenedEvidencePromptPair() {
        contextRunner
                .withPropertyValues(
                        "app.runtime.role=WORKER",
                        "app.interpretation.generation-enabled=true",
                        "app.llm.prompt-version=weekly-interpretation-v10",
                        "app.llm.content-schema-version=2",
                        "app.llm.yandex.folder-id=folder",
                        "app.llm.yandex.api-key=secret-value",
                        "app.llm.yandex.model-uri=gpt://folder/yandexgpt-5.1"
                )
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void workerStartsWithPackagedNarrativeGuardedPromptPair() {
        contextRunner
                .withPropertyValues(
                        "app.runtime.role=WORKER",
                        "app.interpretation.generation-enabled=true",
                        "app.llm.prompt-version=weekly-interpretation-v11",
                        "app.llm.content-schema-version=2",
                        "app.llm.yandex.folder-id=folder",
                        "app.llm.yandex.api-key=secret-value",
                        "app.llm.yandex.model-uri=gpt://folder/yandexgpt-5.1"
                )
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void workerStartsWithPackagedCausalNarrativeGuardedPromptPair() {
        contextRunner
                .withPropertyValues(
                        "app.runtime.role=WORKER",
                        "app.interpretation.generation-enabled=true",
                        "app.llm.prompt-version=weekly-interpretation-v12",
                        "app.llm.content-schema-version=2",
                        "app.llm.yandex.folder-id=folder",
                        "app.llm.yandex.api-key=secret-value",
                        "app.llm.yandex.model-uri=gpt://folder/yandexgpt-5.1"
                )
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void workerStartsWithPackagedPrimarySignalPromptPair() {
        contextRunner
                .withPropertyValues(
                        "app.runtime.role=WORKER",
                        "app.interpretation.generation-enabled=true",
                        "app.llm.prompt-version=weekly-interpretation-v13",
                        "app.llm.content-schema-version=3",
                        "app.llm.yandex.folder-id=folder",
                        "app.llm.yandex.api-key=secret-value",
                        "app.llm.yandex.model-uri=gpt://folder/yandexgpt-5.1"
                )
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void workerStartsWithPackagedStructuredSummaryPromptPair() {
        contextRunner
                .withPropertyValues(
                        "app.runtime.role=WORKER",
                        "app.interpretation.generation-enabled=true",
                        "app.llm.prompt-version=weekly-interpretation-v14",
                        "app.llm.content-schema-version=3",
                        "app.llm.yandex.folder-id=folder",
                        "app.llm.yandex.api-key=secret-value",
                        "app.llm.yandex.model-uri=gpt://folder/yandexgpt-5.1"
                )
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void workerStartsWithPackagedTeamGuardedStructuredSummaryPromptPair() {
        contextRunner
                .withPropertyValues(
                        "app.runtime.role=WORKER",
                        "app.interpretation.generation-enabled=true",
                        "app.llm.prompt-version=weekly-interpretation-v15",
                        "app.llm.content-schema-version=3",
                        "app.llm.yandex.folder-id=folder",
                        "app.llm.yandex.api-key=secret-value",
                        "app.llm.yandex.model-uri=gpt://folder/yandexgpt-5.1"
                )
                .run(context -> assertThat(context).hasNotFailed());
    }


    @Test
    void workerRejectsUnpinnedLatestModelUri() {
        contextRunner
                .withPropertyValues(
                        "app.runtime.role=WORKER",
                        "app.interpretation.generation-enabled=true",
                        "app.llm.yandex.folder-id=folder",
                        "app.llm.yandex.api-key=secret-value",
                        "app.llm.yandex.model-uri=gpt://folder/model/latest"
                )
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void workerRejectsMixedPromptAndSchemaVersions() {
        contextRunner
                .withPropertyValues(
                        "app.runtime.role=WORKER",
                        "app.interpretation.generation-enabled=true",
                        "app.llm.prompt-version=weekly-interpretation-v4",
                        "app.llm.content-schema-version=1",
                        "app.llm.yandex.folder-id=folder",
                        "app.llm.yandex.api-key=secret-value",
                        "app.llm.yandex.model-uri=gpt://folder/yandexgpt-5.1"
                )
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void workerFailsFastWhenModelUriBelongsToAnotherFolder() {
        contextRunner
                .withPropertyValues(
                        "app.runtime.role=WORKER",
                        "app.interpretation.generation-enabled=true",
                        "app.llm.yandex.folder-id=folder-a",
                        "app.llm.yandex.api-key=secret-value",
                        "app.llm.yandex.model-uri=gpt://folder-b/yandexgpt-5.1"
                )
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void workerFailsFastWhenSnapshotWorkerIsEnabledWithoutSnapshotFeature() {
        contextRunner
                .withPropertyValues(
                        "app.runtime.role=WORKER",
                        "app.interpretation.snapshot-worker.enabled=true"
                )
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void workerAcceptsSnapshotWorkerOnlyWithSnapshotFeatureEnabled() {
        contextRunner
                .withPropertyValues(
                        "app.runtime.role=WORKER",
                        "app.interpretation.snapshot-enabled=true",
                        "app.interpretation.snapshot-worker.enabled=true"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(
                            WeeklySnapshotWorkerProperties.class
                    ).enabled()).isTrue();
                });
    }

    @Test
    void workerFailsFastWhenSnapshotPlannerIsEnabledWithoutSnapshotFeature() {
        contextRunner
                .withPropertyValues(
                        "app.runtime.role=WORKER",
                        "app.interpretation.snapshot-planner.enabled=true"
                )
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void workerAcceptsSnapshotPlannerOnlyWithSnapshotFeatureEnabled() {
        contextRunner
                .withPropertyValues(
                        "app.runtime.role=WORKER",
                        "app.interpretation.snapshot-enabled=true",
                        "app.interpretation.snapshot-planner.enabled=true"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(
                            WeeklySnapshotPlannerProperties.class
                    ).enabled()).isTrue();
                });
    }

    @Test
    void generationWorkerFailsFastWithoutRegisteredProviderAdapter() {
        contextRunner
                .withPropertyValues(
                        "app.runtime.role=WORKER",
                        "app.interpretation.generation-enabled=true",
                        "app.interpretation.generation-worker.enabled=true",
                        "app.llm.yandex.folder-id=folder",
                        "app.llm.yandex.api-key=secret-value",
                        "app.llm.yandex.model-uri=gpt://folder/yandexgpt-5.1"
                )
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void workerFailsFastWhenGenerationWorkerIsEnabledWithoutGeneration() {
        contextRunner
                .withPropertyValues(
                        "app.runtime.role=WORKER",
                        "app.interpretation.generation-worker.enabled=true"
                )
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void workerFailsFastWhenGenerationPlannerIsEnabledWithoutGeneration() {
        contextRunner
                .withPropertyValues(
                        "app.runtime.role=WORKER",
                        "app.interpretation.generation-planner.enabled=true"
                )
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void workerAcceptsGenerationPlannerWithGenerationAndCredentials() {
        contextRunner
                .withPropertyValues(
                        "app.runtime.role=WORKER",
                        "app.interpretation.generation-enabled=true",
                        "app.interpretation.generation-planner.enabled=true",
                        "app.llm.yandex.folder-id=folder",
                        "app.llm.yandex.api-key=secret-value",
                        "app.llm.yandex.model-uri=gpt://folder/yandexgpt-5.1"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(
                            LlmAnalysisPlannerProperties.class
                    ).enabled()).isTrue();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
            ApplicationRuntimeProperties.class,
            InterpretationFeatureProperties.class,
            LlmGenerationProperties.class,
            WeeklySnapshotWorkerProperties.class,
            WeeklySnapshotPlannerProperties.class,
            LlmAnalysisPlannerProperties.class,
            LlmAnalysisWorkerProperties.class,
            YandexLlmProperties.class
    })
    @Import(InterpretationWorkerConfiguration.class)
    static class TestConfiguration {
    }
}
