package com.storeanalytics.interpretation.review.ai;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.integration.llm.yandex.YandexLlmMetrics;
import com.storeanalytics.integration.llm.yandex.YandexLlmPolicyProperties;
import com.storeanalytics.integration.llm.yandex.YandexLlmProperties;
import com.storeanalytics.integration.llm.yandex.YandexLlmProviderClient;
import com.storeanalytics.interpretation.generation.LlmProviderClient;
import com.storeanalytics.interpretation.generation.LlmProviderPreflight;
import com.storeanalytics.interpretation.generation.LlmProviderResponseReceipt;
import com.storeanalytics.interpretation.review.PersistedWeeklyReviewSnapshot;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

/** Test-runtime-only, explicit-budget v24 semantic shadow runner. */
public final class WeeklyReviewAiShadowRunner {

    static final String MODE_PLAN = "plan";
    static final String MODE_EXECUTE = "execute";
    static final String CONFIRMATION = "CALL_WEEKLY_REVIEW_AI_SHADOW";

    private final ObjectMapper mapper;
    private final Clock clock;

    WeeklyReviewAiShadowRunner(ObjectMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 0) {
            throw new IllegalArgumentException(
                    "Weekly review AI shadow runner accepts only environment settings"
            );
        }
        ObjectMapper mapper = new ObjectMapper().rebuild()
                .findAndAddModules()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build();
        int exitCode = new WeeklyReviewAiShadowRunner(
                mapper, Clock.systemUTC()
        ).run(System.getenv());
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    int run(Map<String, String> environment) throws IOException {
        Settings settings = Settings.from(environment);
        LlmProviderClient provider = provider(settings);
        List<PreparedCase> cases = prepare(settings, provider);
        BigDecimal maximum = cases.stream()
                .map(value -> value.preflight().estimatedMaximumCost())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        System.out.printf(
                "Weekly review AI shadow plan: corpus=%s cases=%d maximum=%s RUB mode=%s%n",
                WeeklyReviewAiEvaluationCorpus.VERSION,
                cases.size(),
                maximum,
                settings.mode()
        );
        cases.forEach(value -> System.out.printf(
                "case=%s requestHash=%s inputHash=%s estimatedMaximum=%s RUB%n",
                value.caseId(),
                value.prepared().requestHash(),
                value.prepared().inputHash(),
                value.preflight().estimatedMaximumCost()
        ));
        if (MODE_PLAN.equals(settings.mode())) {
            return 0;
        }
        List<PreparedCase> selected = cases.stream()
                .skip(settings.caseOffset())
                .limit(settings.maxPaidCalls())
                .toList();
        BigDecimal selectedMaximum = selected.stream()
                .map(value -> value.preflight().estimatedMaximumCost())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        settings.validateExecution(selectedMaximum);
        prepareOutputDirectory(settings.outputDirectory());

        WeeklyReviewAiSemanticValidator validator =
                new WeeklyReviewAiSemanticValidator(
                        new WeeklyReviewAiStructuralValidator()
                );
        int invalid = 0;
        BigDecimal actual = BigDecimal.ZERO;
        for (PreparedCase value : selected) {
            LlmProviderResponseReceipt receipt = provider.generate(
                    value.prepared().request()
            );
            WeeklyReviewAiValidationResult validation = validator.validate(
                    value.prepared().input(), receipt.responseBody()
            );
            persist(settings.outputDirectory(), value, receipt, validation);
            actual = actual.add(receipt.costAmount());
            if (!validation.semanticValidated()) {
                invalid++;
            }
        }
        System.out.printf(
                "Weekly review AI shadow complete: selected=%d valid=%d invalid=%d actual=%s RUB%n",
                selected.size(), selected.size() - invalid, invalid, actual
        );
        return invalid == 0 ? 0 : 1;
    }

    private List<PreparedCase> prepare(
            Settings settings,
            LlmProviderClient provider
    ) {
        WeeklyReviewAiInputCompactor compactor = mock(
                WeeklyReviewAiInputCompactor.class
        );
        WeeklyReviewAiProviderRequestFactory factory =
                new WeeklyReviewAiProviderRequestFactory(
                        compactor,
                        new WeeklyReviewAiContentCodec()
                );
        WeeklyReviewAiBudgetGuard budget = new WeeklyReviewAiBudgetGuard(
                WeeklyReviewAiTestProperties.properties(true, false, true)
        );
        List<PreparedCase> result = new ArrayList<>();
        int index = 0;
        for (WeeklyReviewAiEvaluationCorpus.OnlineCase evaluation
                : WeeklyReviewAiEvaluationCorpus.onlineCases()) {
            PersistedWeeklyReviewSnapshot snapshot = mock(
                    PersistedWeeklyReviewSnapshot.class
            );
            WeeklyReviewResponse response = mock(WeeklyReviewResponse.class);
            when(snapshot.response()).thenReturn(response);
            when(compactor.compact(response)).thenReturn(evaluation.input());
            Instant now = clock.instant();
            PreparedWeeklyReviewAiRequest prepared = factory.prepare(
                    new WeeklyReviewAiProviderRequestCommand(
                            stableUuid(evaluation.id()),
                            snapshot,
                            "YANDEX",
                            settings.modelUri(),
                            new BigDecimal("0.1"),
                            1400,
                            now,
                            Duration.ofMinutes(3),
                            now.plus(Duration.ofMinutes(10)),
                            List.of()
                    )
            );
            LlmProviderPreflight preflight = provider.preflight(
                    prepared.request()
            );
            budget.validate(prepared.request(), preflight, BigDecimal.ZERO);
            result.add(new PreparedCase(
                    evaluation.id(), index++, prepared, preflight
            ));
        }
        return List.copyOf(result);
    }

    private LlmProviderClient provider(Settings settings) {
        YandexLlmProperties properties = new YandexLlmProperties(
                settings.folderId(),
                settings.apiKey(),
                settings.modelUri(),
                Duration.ofSeconds(5),
                Duration.ofMinutes(3)
        );
        YandexLlmPolicyProperties policy = new YandexLlmPolicyProperties(
                32_768,
                1_048_576,
                settings.inputPrice(),
                settings.inputPrice(),
                settings.outputPrice()
        );
        return new YandexLlmProviderClient(
                properties,
                policy,
                mapper,
                new YandexLlmMetrics(new SimpleMeterRegistry()),
                clock
        );
    }

    private void persist(
            Path directory,
            PreparedCase value,
            LlmProviderResponseReceipt receipt,
            WeeklyReviewAiValidationResult validation
    ) throws IOException {
        writeNew(
                directory.resolve(value.caseId() + ".input.json"),
                value.prepared().request().inputJson()
        );
        writeNew(
                directory.resolve(value.caseId() + ".json"),
                receipt.responseBody()
        );
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("corpusVersion", WeeklyReviewAiEvaluationCorpus.VERSION);
        metadata.put("caseId", value.caseId());
        metadata.put("requestHash", value.prepared().requestHash());
        metadata.put("inputHash", value.prepared().inputHash());
        metadata.put("providerRequestId", receipt.providerRequestId());
        metadata.put("resolvedModel", receipt.resolvedModel());
        metadata.put("inputTokens", receipt.inputTokens());
        metadata.put("outputTokens", receipt.outputTokens());
        metadata.put("costAmount", receipt.costAmount());
        metadata.put("costCurrency", receipt.costCurrency());
        metadata.put("validationOutcome", validation.outcome().name());
        metadata.put("semanticValidated", validation.semanticValidated());
        metadata.put("violations", validation.violations());
        try {
            writeNew(
                    directory.resolve(value.caseId() + ".receipt.json"),
                    mapper.writeValueAsString(metadata)
            );
        } catch (JacksonException exception) {
            throw new IOException("Shadow receipt cannot be encoded", exception);
        }
    }

    private void prepareOutputDirectory(Path directory) throws IOException {
        if (Files.exists(directory)) {
            throw new IllegalArgumentException(
                    "Shadow output directory already exists: " + directory
            );
        }
        Files.createDirectories(directory);
    }

    private void writeNew(Path path, String value) throws IOException {
        Files.writeString(
                path,
                value,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        );
    }

    private UUID stableUuid(String value) {
        return UUID.nameUUIDFromBytes(
                (WeeklyReviewAiEvaluationCorpus.VERSION + ":" + value)
                        .getBytes(StandardCharsets.UTF_8)
        );
    }

    record PreparedCase(
            String caseId,
            int index,
            PreparedWeeklyReviewAiRequest prepared,
            LlmProviderPreflight preflight
    ) {
    }

    record Settings(
            String mode,
            String folderId,
            String apiKey,
            String modelUri,
            BigDecimal inputPrice,
            BigDecimal outputPrice,
            int maxPaidCalls,
            int caseOffset,
            BigDecimal maxCostRub,
            String confirmation,
            Path outputDirectory
    ) {

        static Settings from(Map<String, String> environment) {
            String mode = environment.getOrDefault(
                    "WEEKLY_REVIEW_AI_EVAL_MODE", MODE_PLAN
            );
            if (!MODE_PLAN.equals(mode) && !MODE_EXECUTE.equals(mode)) {
                throw new IllegalArgumentException("Unknown weekly review AI eval mode");
            }
            boolean execute = MODE_EXECUTE.equals(mode);
            String folder = environment.getOrDefault(
                    "YANDEX_AI_FOLDER_ID", execute ? "" : "shadow-eval"
            );
            String apiKey = environment.getOrDefault(
                    "YANDEX_AI_API_KEY", execute ? "" : "not-used-in-plan"
            );
            String model = environment.getOrDefault(
                    "YANDEX_AI_MODEL_URI",
                    "gpt://" + folder + "/yandexgpt-5.1"
            );
            int calls = integer(environment, "WEEKLY_REVIEW_AI_EVAL_MAX_PAID_CALLS", 0);
            BigDecimal cap = decimal(
                    environment.get("WEEKLY_REVIEW_AI_EVAL_MAX_COST_RUB")
            );
            Path output = Path.of(environment.getOrDefault(
                    "WEEKLY_REVIEW_AI_EVAL_OUTPUT_DIR",
                    "build/weekly-review-ai-eval/unconfigured"
            )).normalize();
            Settings settings = new Settings(
                    mode,
                    folder,
                    apiKey,
                    model,
                    decimal(environment.getOrDefault(
                            "YANDEX_AI_INPUT_RUB_PER_THOUSAND_TOKENS", "0.8"
                    )),
                    decimal(environment.getOrDefault(
                            "YANDEX_AI_OUTPUT_RUB_PER_THOUSAND_TOKENS", "0.8"
                    )),
                    calls,
                    integer(environment,
                            "WEEKLY_REVIEW_AI_EVAL_CASE_OFFSET", 0),
                    cap,
                    environment.getOrDefault(
                            "CONFIRM_WEEKLY_REVIEW_AI_SHADOW", ""
                    ),
                    output
            );
            settings.validateShape();
            return settings;
        }

        void validateExecution(BigDecimal selectedMaximum) {
            if (!MODE_EXECUTE.equals(mode)) {
                throw new IllegalStateException("Execution gate used outside execute mode");
            }
            if (!CONFIRMATION.equals(confirmation)) {
                throw new IllegalArgumentException(
                        "CONFIRM_WEEKLY_REVIEW_AI_SHADOW is missing"
                );
            }
            if (selectedMaximum.compareTo(maxCostRub) > 0) {
                throw new IllegalArgumentException(
                        "Selected shadow maximum is above explicit cap"
                );
            }
        }

        private void validateShape() {
            boolean execute = MODE_EXECUTE.equals(mode);
            if (folderId.isBlank() || apiKey.isBlank()
                    || !modelUri.startsWith("gpt://" + folderId + "/")
                    || modelUri.endsWith("/latest")) {
                throw new IllegalArgumentException(
                        "Shadow provider configuration must use a versioned model"
                );
            }
            if (inputPrice == null || inputPrice.signum() <= 0
                    || outputPrice == null || outputPrice.signum() <= 0) {
                throw new IllegalArgumentException(
                        "Shadow price coefficients must be positive"
                );
            }
            if (execute && (maxPaidCalls < 1 || maxPaidCalls > 4
                    || caseOffset < 0 || caseOffset + maxPaidCalls > 4
                    || maxCostRub == null || maxCostRub.signum() <= 0)) {
                throw new IllegalArgumentException(
                        "Execute mode requires bounded paid calls and RUB cap"
                );
            }
            Path allowedRoot = Path.of("build/weekly-review-ai-eval");
            if (execute && (outputDirectory.isAbsolute()
                    || !outputDirectory.startsWith(allowedRoot))) {
                throw new IllegalArgumentException(
                        "Shadow output directory must stay under build/weekly-review-ai-eval"
                );
            }
        }

        private static int integer(
                Map<String, String> environment,
                String key,
                int defaultValue
        ) {
            String value = environment.get(key);
            return value == null ? defaultValue : Integer.parseInt(value);
        }

        private static BigDecimal decimal(String value) {
            return value == null ? null : new BigDecimal(value);
        }
    }
}
