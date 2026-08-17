package com.storeanalytics.interpretation.generation;

import com.storeanalytics.integration.llm.yandex.YandexLlmMetrics;
import com.storeanalytics.integration.llm.yandex.YandexLlmPolicyProperties;
import com.storeanalytics.integration.llm.yandex.YandexLlmProperties;
import com.storeanalytics.integration.llm.yandex.YandexLlmProviderClient;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput;
import com.storeanalytics.interpretation.snapshot.PersistedWeeklySnapshot;
import com.storeanalytics.interpretation.snapshot.WeeklyAnalyticsFactsQuery;
import com.storeanalytics.interpretation.snapshot.WeeklySnapshotPayload;
import com.storeanalytics.interpretation.snapshot.WeeklySnapshotStore;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

/**
 * Local-only v4/v15 shadow harness. It lives in test sources and is never packaged
 * into the production application.
 */
public final class LlmEvalShadowRunner {

    static final String MODE_PLAN = "plan";
    static final String MODE_EXECUTE = "execute";
    static final String EXECUTION_CONFIRMATION = "CALL_YANDEX_SHADOW";
    static final String PLACEHOLDER_FOLDER = "shadow-eval";
    static final String PLACEHOLDER_MODEL =
            "gpt://" + PLACEHOLDER_FOLDER + "/yandexgpt-5.1";
    static final int DEFAULT_MAX_OUTPUT_TOKENS = 8_000;
    static final int MAX_REQUEST_BYTES = 524_288;
    static final BigDecimal MAX_REQUEST_COST_RUB = new BigDecimal("50.00");

    private final ObjectMapper objectMapper;
    private final Clock clock;

    LlmEvalShadowRunner(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 0) {
            throw new IllegalArgumentException(
                    "The shadow runner is configured only through documented environment variables"
            );
        }
        ObjectMapper mapper = new ObjectMapper()
                .rebuild()
                .findAndAddModules()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build();
        int exitCode = new LlmEvalShadowRunner(mapper, Clock.systemUTC()).run(
                System.getenv()
        );
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    int run(Map<String, String> environment) throws IOException {
        Settings settings = Settings.from(environment);
        List<PreparedEntry> matrix = prepareMatrix(settings);
        MatrixState state = inspectState(matrix, settings);
        List<PreparedEntry> candidates = state.candidates();
        int selectionLimit = MODE_EXECUTE.equals(settings.mode())
                ? settings.maxPaidCalls()
                : candidates.size();
        List<PreparedEntry> selected = candidates.stream()
                .limit(selectionLimit)
                .toList();
        BigDecimal selectedMaximumCost = selected.stream()
                .map(entry -> entry.preflight().estimatedMaximumCost())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        writePlan(settings, matrix, state, selected, selectedMaximumCost);
        printPlan(settings, matrix, state, selected, selectedMaximumCost);
        if (MODE_PLAN.equals(settings.mode())) {
            return 0;
        }
        validateExecution(settings, selected, selectedMaximumCost);
        if (selected.isEmpty()) {
            System.out.println("No pending shadow calls selected.");
            return 0;
        }
        LlmProviderClient provider = provider(settings, false);
        int succeeded = 0;
        int failed = 0;
        BigDecimal actualCost = BigDecimal.ZERO;
        for (PreparedEntry entry : selected) {
            try {
                LlmProviderResponseReceipt receipt = provider.generate(
                        withFreshDeadline(entry.prepared().request(), settings)
                );
                persistSuccess(settings, entry, receipt);
                succeeded++;
                actualCost = actualCost.add(receipt.costAmount());
                System.out.printf(
                        "shadow success case=%s configuration=%s cost=%s RUB%n",
                        entry.caseId(), entry.configuration().id(), receipt.costAmount()
                );
            } catch (LlmProviderException exception) {
                persistFailure(settings, entry, exception);
                failed++;
                System.err.printf(
                        "shadow failure case=%s configuration=%s code=%s outcome=%s%n",
                        entry.caseId(), entry.configuration().id(),
                        exception.failureCode(), exception.outcome()
                );
            }
        }
        System.out.printf(
                "Shadow batch finished: selected=%d succeeded=%d failed=%d actualSuccessfulCost=%s RUB.%n",
                selected.size(), succeeded, failed, actualCost.setScale(6, RoundingMode.CEILING)
        );
        return failed == 0 ? 0 : 1;
    }

    private List<PreparedEntry> prepareMatrix(Settings settings) throws IOException {
        if (!Files.isDirectory(settings.inputsDirectory())) {
            throw new IllegalArgumentException(
                    "Missing exported inputs directory: " + settings.inputsDirectory()
            );
        }
        List<Configuration> configurations = configurations(settings.datasetPath());
        List<Path> inputFiles;
        try (var files = Files.list(settings.inputsDirectory())) {
            inputFiles = files
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        if (inputFiles.isEmpty()) {
            throw new IllegalArgumentException("No exported evaluation inputs were found");
        }
        LlmProviderClient preflightProvider = provider(settings, true);
        List<PreparedEntry> matrix = new ArrayList<>();
        for (Path inputFile : inputFiles) {
            String filename = inputFile.getFileName().toString();
            String caseId = filename.substring(0, filename.length() - ".json".length());
            WeeklyInterpretationInput input = objectMapper.readValue(
                    Files.readString(inputFile, StandardCharsets.UTF_8),
                    WeeklyInterpretationInput.class
            );
            String providerInput = null;
            for (Configuration configuration : configurations) {
                PreparedLlmProviderRequest prepared = prepare(
                        settings, caseId, configuration, input
                );
                if (providerInput == null) {
                    providerInput = prepared.request().inputJson();
                } else if (!providerInput.equals(prepared.request().inputJson())) {
                    throw new IllegalStateException(
                            "evaluation provider inputs differ for case " + caseId
                    );
                }
                LlmProviderPreflight preflight = preflightProvider.preflight(
                        prepared.request()
                );
                validateProductionBounds(prepared.request(), preflight);
                matrix.add(new PreparedEntry(
                        caseId,
                        configuration,
                        prepared,
                        preflight,
                        requestBytes(prepared.request())
                ));
            }
        }
        return List.copyOf(matrix);
    }

    private List<Configuration> configurations(Path datasetPath) throws IOException {
        JsonNode root = objectMapper.readTree(
                Files.readString(datasetPath, StandardCharsets.UTF_8)
        );
        List<Configuration> values = new ArrayList<>();
        for (JsonNode node : root.path("configurations")) {
            Configuration configuration = new Configuration(
                    node.path("id").asText(),
                    node.path("promptVersion").asText(),
                    node.path("contentSchemaVersion").asInt()
            );
            if (!("v4".equals(configuration.id())
                    || "v15".equals(configuration.id()))) {
                throw new IllegalArgumentException(
                        "Shadow runner only supports the reviewed v4/v15 matrix"
                );
            }
            values.add(configuration);
        }
        if (values.size() != 2) {
            throw new IllegalArgumentException(
                    "Shadow dataset must contain exactly the v4 and v15 configurations"
            );
        }
        return List.copyOf(values);
    }

    private PreparedLlmProviderRequest prepare(
            Settings settings,
            String caseId,
            Configuration configuration,
            WeeklyInterpretationInput input
    ) {
        PersistedWeeklySnapshot snapshot = snapshot(input);
        WeeklySnapshotStore snapshotStore = new WeeklySnapshotStore(null, null) {
            @Override
            public Optional<PersistedWeeklySnapshot> findById(UUID snapshotId) {
                return snapshot.id().equals(snapshotId)
                        ? Optional.of(snapshot) : Optional.empty();
            }
        };
        LlmValidationRetryPromptFactory retryPromptFactory =
                new LlmValidationRetryPromptFactory(null, objectMapper);
        LlmProviderRequestFactory factory = new LlmProviderRequestFactory(
                snapshotStore,
                retryPromptFactory,
                new LlmProviderInputCompactor()
        );
        Instant now = clock.instant();
        LlmAnalysisJob job = new LlmAnalysisJob(
                stableUuid(caseId + ":" + configuration.id()),
                snapshot.id(),
                1,
                LlmAnalysisTriggerType.MODEL_CHANGE,
                null,
                "YANDEX",
                settings.modelUri(),
                "shadow-eval-v1",
                configuration.contentSchemaVersion(),
                configuration.promptVersion(),
                "shadow-eval-v1",
                "shadow-eval-v1",
                generationParameters(settings),
                "0".repeat(64),
                LlmAnalysisJobStatus.PENDING,
                LlmAnalysisPhase.PREPARE,
                0,
                0,
                0,
                0,
                0,
                now,
                now.plus(settings.callTimeout()),
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                0,
                now,
                now
        );
        return factory.prepare(job, now, settings.callTimeout());
    }

    private PersistedWeeklySnapshot snapshot(WeeklyInterpretationInput input) {
        WeeklyInterpretationInput.Snapshot header = input.snapshot();
        UUID storeId = stableUuid("shadow-store");
        WeeklyAnalyticsFactsQuery query = new WeeklyAnalyticsFactsQuery(
                storeId,
                new StoreKpiPeriod(header.period().start(), header.period().end()),
                new StoreKpiPeriod(
                        header.comparisonPeriod().start(),
                        header.comparisonPeriod().end()
                )
        );
        Instant now = clock.instant();
        return new PersistedWeeklySnapshot(
                header.snapshotRef(),
                storeId,
                query,
                header.timezone(),
                header.revision(),
                null,
                "EVALUATION",
                null,
                stableUuid("shadow-sync:" + header.snapshotRef()),
                now.minusSeconds(60),
                now.minusSeconds(60),
                header.qualityStatus(),
                header.versions(),
                new WeeklySnapshotPayload(
                        input.contractVersion(), input.manifest(), input.facts()
                ),
                header.factsHash(),
                List.of(),
                now.minusSeconds(60)
        );
    }

    private MatrixState inspectState(
            List<PreparedEntry> matrix,
            Settings settings
    ) throws IOException {
        List<PreparedEntry> candidates = new ArrayList<>();
        int completed = 0;
        int failed = 0;
        for (PreparedEntry entry : matrix) {
            Path response = responsePath(settings, entry);
            Path receipt = receiptPath(settings, entry);
            Path failure = failurePath(settings, entry);
            if (Files.exists(response)) {
                verifyEvaluationArtifact(
                        receipt, evaluationHash(entry.prepared().request()),
                        "completed response"
                );
                JsonNode parsed = objectMapper.readTree(
                        Files.readString(response, StandardCharsets.UTF_8)
                );
                if (parsed == null || !parsed.isObject()) {
                    throw new IllegalStateException(
                            "Existing shadow response is not a JSON object: " + response
                    );
                }
                completed++;
            } else if (Files.exists(failure)) {
                failed++;
                if (settings.retryFailures()) {
                    candidates.add(entry);
                }
            } else {
                candidates.add(entry);
            }
        }
        return new MatrixState(completed, failed, List.copyOf(candidates));
    }

    void verifyEvaluationArtifact(
            Path artifact,
            String expectedEvaluationHash,
            String label
    ) throws IOException {
        if (!Files.isRegularFile(artifact)) {
            throw new IllegalStateException(
                    "Missing metadata for shadow " + label + ": " + artifact
            );
        }
        JsonNode metadata = objectMapper.readTree(
                Files.readString(artifact, StandardCharsets.UTF_8)
        );
        String actualEvaluationHash = metadata.path("evaluationHash").asText();
        if (!expectedEvaluationHash.equals(actualEvaluationHash)) {
            throw new IllegalStateException(
                    "Stale shadow " + label + " has a different evaluation hash: "
                            + artifact
            );
        }
    }

    void validateExecution(
            Settings settings,
            List<PreparedEntry> selected,
            BigDecimal selectedMaximumCost
    ) {
        if (!EXECUTION_CONFIRMATION.equals(settings.confirmation())) {
            throw new IllegalArgumentException(
                    "Set CONFIRM_YANDEX_LLM_SHADOW=" + EXECUTION_CONFIRMATION
            );
        }
        if (settings.maxPaidCalls() < 1) {
            throw new IllegalArgumentException("LLM_EVAL_MAX_PAID_CALLS must be positive");
        }
        if (settings.maxCostRub() == null
                || settings.maxCostRub().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("LLM_EVAL_MAX_COST_RUB must be positive");
        }
        if (selectedMaximumCost.compareTo(settings.maxCostRub()) > 0) {
            throw new IllegalArgumentException(
                    "Selected calls have a maximum estimated cost of "
                            + selectedMaximumCost + " RUB, above the explicit cap of "
                            + settings.maxCostRub() + " RUB"
            );
        }
        if (settings.apiKey().isBlank()) {
            throw new IllegalArgumentException("YANDEX_AI_API_KEY is required for execution");
        }
        if (settings.placeholderModel()) {
            throw new IllegalArgumentException(
                    "YANDEX_AI_FOLDER_ID and versioned YANDEX_AI_MODEL_URI are required"
            );
        }
    }

    private void validateProductionBounds(
            LlmProviderRequest request,
            LlmProviderPreflight preflight
    ) {
        if (requestBytes(request) > MAX_REQUEST_BYTES) {
            throw new IllegalStateException("Shadow request exceeds production byte budget");
        }
        long total = (long) preflight.estimatedInputTokens()
                + request.maxOutputTokens();
        if (total > preflight.contextWindowTokens()) {
            throw new IllegalStateException("Shadow request exceeds provider context window");
        }
        if (!"RUB".equals(preflight.costCurrency())) {
            throw new IllegalStateException("Shadow preflight returned unsupported currency");
        }
        if (preflight.estimatedMaximumCost().compareTo(MAX_REQUEST_COST_RUB) > 0) {
            throw new IllegalStateException(
                    "Shadow request exceeds production per-request cost budget"
            );
        }
    }

    private LlmProviderClient provider(Settings settings, boolean planning) {
        String folder = planning && settings.placeholderModel()
                ? PLACEHOLDER_FOLDER : settings.folderId();
        String apiKey = planning ? "dry-run-not-sent" : settings.apiKey();
        YandexLlmProperties properties = new YandexLlmProperties(
                folder,
                apiKey,
                settings.modelUri(),
                Duration.ofSeconds(5),
                settings.callTimeout()
        );
        YandexLlmPolicyProperties policy = new YandexLlmPolicyProperties(
                32_768,
                1_048_576,
                settings.inputPrice(),
                settings.cachedInputPrice(),
                settings.outputPrice()
        );
        return new YandexLlmProviderClient(
                properties,
                policy,
                objectMapper,
                new YandexLlmMetrics(new SimpleMeterRegistry()),
                clock
        );
    }

    private LlmProviderRequest withFreshDeadline(
            LlmProviderRequest source,
            Settings settings
    ) {
        return new LlmProviderRequest(
                source.jobId(),
                source.providerCode(),
                source.requestedModel(),
                source.systemPrompt(),
                source.inputJson(),
                source.responseSchemaJson(),
                source.temperature(),
                source.maxOutputTokens(),
                clock.instant().plus(settings.callTimeout())
        );
    }

    private void persistSuccess(
            Settings settings,
            PreparedEntry entry,
            LlmProviderResponseReceipt receipt
    ) throws IOException {
        Path response = responsePath(settings, entry);
        atomicWrite(response, receipt.responseBody());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("caseId", entry.caseId());
        metadata.put("configurationId", entry.configuration().id());
        metadata.put("requestHash", entry.prepared().requestHash());
        metadata.put("resolvedModel", receipt.resolvedModel());
        metadata.put("providerRequestId", receipt.providerRequestId());
        metadata.put("inputTokens", receipt.inputTokens());
        metadata.put("outputTokens", receipt.outputTokens());
        metadata.put("cachedInputTokens", receipt.cachedInputTokens());
        metadata.put("reasoningTokens", receipt.reasoningTokens());
        metadata.put("totalTokens", receipt.totalTokens());
        metadata.put("costAmount", receipt.costAmount());
        metadata.put("costCurrency", receipt.costCurrency());
        metadata.put("latencyMs", receipt.latencyMs());
        metadata.put("evaluationHash", evaluationHash(
                entry.prepared().request()));
        metadata.put("httpStatus", receipt.httpStatus());
        metadata.put("recordedAt", clock.instant().toString());
        atomicWrite(receiptPath(settings, entry), prettyJson(metadata));
        Files.deleteIfExists(failurePath(settings, entry));
    }

    private void persistFailure(
            Settings settings,
            PreparedEntry entry,
            LlmProviderException exception
    ) throws IOException {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("caseId", entry.caseId());
        failure.put("configurationId", entry.configuration().id());
        failure.put("requestHash", entry.prepared().requestHash());
        failure.put("evaluationHash", evaluationHash(
                entry.prepared().request()));
        failure.put("failureCode", exception.failureCode());
        failure.put("outcome", exception.outcome().name());
        failure.put("httpStatus", exception.httpStatus());
        failure.put("retryable", exception.isRetryable());
        failure.put("recordedAt", clock.instant().toString());
        atomicReplace(failurePath(settings, entry), prettyJson(failure));
    }

    private void writePlan(
            Settings settings,
            List<PreparedEntry> matrix,
            MatrixState state,
            List<PreparedEntry> selected,
            BigDecimal selectedMaximumCost
    ) throws IOException {
        List<Map<String, Object>> entries = matrix.stream().map(entry -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("caseId", entry.caseId());
            value.put("configurationId", entry.configuration().id());
            value.put("promptVersion", entry.configuration().promptVersion());
            value.put("contentSchemaVersion",
                    entry.configuration().contentSchemaVersion());
            value.put("requestHash", entry.prepared().requestHash());
            value.put("evaluationHash", evaluationHash(
                    entry.prepared().request()));
            value.put("providerInputHash", sha256(
                    entry.prepared().request().inputJson()
            ));
            value.put("requestBytes", entry.requestBytes());
            value.put("estimatedInputTokens",
                    entry.preflight().estimatedInputTokens());
            value.put("maxOutputTokens",
                    entry.prepared().request().maxOutputTokens());
            value.put("estimatedMaximumCostRub",
                    entry.preflight().estimatedMaximumCost());
            return value;
        }).toList();
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("generatedAt", clock.instant().toString());
        plan.put("mode", settings.mode());
        plan.put("modelUri", settings.modelUri());
        plan.put("placeholderModel", settings.placeholderModel());
        plan.put("matrixSize", matrix.size());
        plan.put("completedResponses", state.completed());
        plan.put("recordedFailures", state.failed());
        plan.put("pendingResponses", state.candidates().size());
        plan.put("selectedCalls", selected.size());
        plan.put("selectedMaximumCostRub", selectedMaximumCost);
        plan.put("inputRubPerThousandTokens", settings.inputPrice());
        plan.put("cachedInputRubPerThousandTokens", settings.cachedInputPrice());
        plan.put("outputRubPerThousandTokens", settings.outputPrice());
        plan.put("entries", entries);
        atomicReplace(settings.planPath(), prettyJson(plan));
    }

    private void printPlan(
            Settings settings,
            List<PreparedEntry> matrix,
            MatrixState state,
            List<PreparedEntry> selected,
            BigDecimal selectedMaximumCost
    ) {
        System.out.printf(
                "Shadow plan: mode=%s matrix=%d completed=%d failures=%d "
                        + "pending=%d selected=%d maxCost=%s RUB model=%s.%n",
                settings.mode(), matrix.size(), state.completed(), state.failed(),
                state.candidates().size(), selected.size(), selectedMaximumCost,
                settings.placeholderModel() ? "placeholder" : "configured"
        );
        System.out.println("Plan artifact: " + settings.planPath());
    }

    private String prettyJson(Object value) throws JacksonException {
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value)
                + System.lineSeparator();
    }

    private void atomicWrite(Path target, String content) throws IOException {
        if (Files.exists(target)) {
            throw new IllegalStateException("Refusing to overwrite artifact: " + target);
        }
        writeAndMove(target, content, false);
    }

    private void atomicReplace(Path target, String content) throws IOException {
        writeAndMove(target, content, true);
    }

    private void writeAndMove(Path target, String content, boolean replace)
            throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(
                target.getFileName() + ".tmp-" + UUID.randomUUID()
        );
        try {
            Files.writeString(
                    temporary,
                    content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            );
            try {
                if (replace) {
                    Files.move(
                            temporary,
                            target,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING
                    );
                } else {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                }
            } catch (AtomicMoveNotSupportedException exception) {
                if (replace) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(temporary, target);
                }
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path responsePath(Settings settings, PreparedEntry entry) {
        return settings.responsesDirectory()
                .resolve(entry.caseId())
                .resolve(entry.configuration().id() + ".json");
    }

    private Path receiptPath(Settings settings, PreparedEntry entry) {
        return settings.receiptsDirectory()
                .resolve(entry.caseId())
                .resolve(entry.configuration().id() + ".json");
    }

    private Path failurePath(Settings settings, PreparedEntry entry) {
        return settings.failuresDirectory()
                .resolve(entry.caseId())
                .resolve(entry.configuration().id() + ".json");
    }

    private int requestBytes(LlmProviderRequest request) {
        return request.systemPrompt().getBytes(StandardCharsets.UTF_8).length
                + request.inputJson().getBytes(StandardCharsets.UTF_8).length
                + request.responseSchemaJson().getBytes(StandardCharsets.UTF_8).length;
    }

    private String generationParameters(Settings settings) {
        return "{\"temperature\":" + settings.temperature()
                + ",\"maxOutputTokens\":" + settings.maxOutputTokens()
                + ",\"maxProviderCalls\":1}";
    }

    private static UUID stableUuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String evaluationHash(
            LlmProviderRequest request
    ) {
        String separator = String.valueOf((char) 31);
        return sha256(String.join(
                separator,
                request.systemPrompt(),
                request.inputJson(),
                request.responseSchemaJson(),
                request.temperature().toPlainString(),
                Integer.toString(request.maxOutputTokens())
        ));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)
                    )
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    record Configuration(
            String id,
            String promptVersion,
            int contentSchemaVersion
    ) {
    }

    record PreparedEntry(
            String caseId,
            Configuration configuration,
            PreparedLlmProviderRequest prepared,
            LlmProviderPreflight preflight,
            int requestBytes
    ) {
    }

    record MatrixState(
            int completed,
            int failed,
            List<PreparedEntry> candidates
    ) {
    }

    record Settings(
            String mode,
            Path datasetPath,
            Path inputsDirectory,
            Path responsesDirectory,
            Path receiptsDirectory,
            Path failuresDirectory,
            Path planPath,
            String folderId,
            String apiKey,
            String modelUri,
            boolean placeholderModel,
            BigDecimal temperature,
            int maxOutputTokens,
            Duration callTimeout,
            BigDecimal inputPrice,
            BigDecimal cachedInputPrice,
            BigDecimal outputPrice,
            int maxPaidCalls,
            BigDecimal maxCostRub,
            String confirmation,
            boolean retryFailures
    ) {

        static Settings from(Map<String, String> environment) {
            String mode = value(environment, "LLM_EVAL_MODE", MODE_PLAN);
            if (!(MODE_PLAN.equals(mode) || MODE_EXECUTE.equals(mode))) {
                throw new IllegalArgumentException("LLM_EVAL_MODE must be plan or execute");
            }
            String folder = value(environment, "YANDEX_AI_FOLDER_ID", "");
            String model = value(environment, "YANDEX_AI_MODEL_URI", "");
            boolean placeholder = folder.isBlank() && model.isBlank();
            if (placeholder) {
                folder = PLACEHOLDER_FOLDER;
                model = PLACEHOLDER_MODEL;
            } else if (folder.isBlank()
                    || !model.matches("gpt://[A-Za-z0-9_-]{4,100}/[A-Za-z0-9._/-]{2,160}")
                    || !model.startsWith("gpt://" + folder + "/")
                    || model.endsWith("/latest")) {
                throw new IllegalArgumentException(
                        "YANDEX_AI_MODEL_URI must be versioned and belong to YANDEX_AI_FOLDER_ID"
                );
            }
            Path responses = path(
                    environment,
                    "LLM_EVAL_RESPONSES_DIR",
                    "build/llm-eval/responses"
            );
            Path artifacts = path(
                    environment,
                    "LLM_EVAL_ARTIFACTS_DIR",
                    "build/llm-eval/shadow"
            );
            Settings settings = new Settings(
                    mode,
                    path(environment, "LLM_EVAL_DATASET",
                            "scripts/llm-eval/dataset-v2.json"),
                    path(environment, "LLM_EVAL_INPUTS_DIR",
                            "build/llm-eval/inputs"),
                    responses,
                    artifacts.resolve("receipts").normalize(),
                    artifacts.resolve("failures").normalize(),
                    artifacts.resolve("plan.json").normalize(),
                    folder,
                    value(environment, "YANDEX_AI_API_KEY", ""),
                    model,
                    placeholder,
                    decimal(environment, "LLM_TEMPERATURE", "0.2"),
                    integer(environment, "LLM_MAX_OUTPUT_TOKENS",
                            DEFAULT_MAX_OUTPUT_TOKENS),
                    Duration.ofSeconds(integer(
                            environment, "LLM_EVAL_CALL_TIMEOUT_SECONDS", 180
                    )),
                    decimal(environment,
                            "YANDEX_AI_INPUT_RUB_PER_THOUSAND_TOKENS", "0.8"),
                    decimal(environment,
                            "YANDEX_AI_CACHED_INPUT_RUB_PER_THOUSAND_TOKENS", "0.8"),
                    decimal(environment,
                            "YANDEX_AI_OUTPUT_RUB_PER_THOUSAND_TOKENS", "0.8"),
                    integer(environment, "LLM_EVAL_MAX_PAID_CALLS", 0),
                    optionalDecimal(environment, "LLM_EVAL_MAX_COST_RUB"),
                    value(environment, "CONFIRM_YANDEX_LLM_SHADOW", ""),
                    "RETRY".equals(value(
                            environment, "LLM_EVAL_RETRY_FAILURES", ""
                    ))
            );
            if (settings.temperature().compareTo(BigDecimal.ZERO) < 0
                    || settings.temperature().compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException(
                        "LLM_TEMPERATURE must be between zero and one"
                );
            }
            if (settings.maxOutputTokens() < 512
                    || settings.maxOutputTokens() > 16_000) {
                throw new IllegalArgumentException(
                        "LLM_MAX_OUTPUT_TOKENS must be between 512 and 16000"
                );
            }
            if (settings.callTimeout().isZero()
                    || settings.callTimeout().isNegative()
                    || settings.callTimeout().compareTo(Duration.ofMinutes(10)) > 0) {
                throw new IllegalArgumentException(
                        "LLM_EVAL_CALL_TIMEOUT_SECONDS must be between 1 and 600"
                );
            }
            if (settings.inputPrice().signum() <= 0
                    || settings.cachedInputPrice().signum() <= 0
                    || settings.outputPrice().signum() <= 0) {
                throw new IllegalArgumentException(
                        "Yandex shadow price coefficients must be positive"
                );
            }
            return settings;
        }

        private static Path path(
                Map<String, String> environment,
                String key,
                String fallback
        ) {
            Path path = Path.of(value(environment, key, fallback))
                    .toAbsolutePath()
                    .normalize();
            Path repository = Path.of("").toAbsolutePath().normalize();
            if (!path.startsWith(repository)) {
                throw new IllegalArgumentException(
                        key + " must stay inside the repository"
                );
            }
            return path;
        }

        private static String value(
                Map<String, String> environment,
                String key,
                String fallback
        ) {
            return environment.getOrDefault(key, fallback).trim();
        }

        private static int integer(
                Map<String, String> environment,
                String key,
                int fallback
        ) {
            String value = value(environment, key, Integer.toString(fallback));
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(key + " must be an integer", exception);
            }
        }

        private static BigDecimal decimal(
                Map<String, String> environment,
                String key,
                String fallback
        ) {
            BigDecimal value = optionalDecimal(environment, key);
            return value == null ? new BigDecimal(fallback) : value;
        }

        private static BigDecimal optionalDecimal(
                Map<String, String> environment,
                String key
        ) {
            String value = value(environment, key, "");
            try {
                return value.isBlank() ? null : new BigDecimal(value);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(key + " must be a decimal", exception);
            }
        }
    }
}
