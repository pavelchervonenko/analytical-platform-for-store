package com.storeanalytics.interpretation.review.ai;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import com.storeanalytics.interpretation.generation.LlmProviderRequest;
import com.storeanalytics.interpretation.review.PersistedWeeklyReviewSnapshot;
import com.storeanalytics.interpretation.validation.LlmJsonSchemaValidator;
import com.storeanalytics.interpretation.validation.StructuralValidationViolation;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Builds the bounded active schema4 provider request from an exact V45 snapshot. */
@Component
public final class WeeklyReviewAiProviderRequestFactory {

    private final WeeklyReviewAiInputCompactor compactor;
    private final WeeklyReviewAiContentCodec codec;
    private final ObjectMapper mapper;
    private final ObjectWriter writer;
    private final LlmJsonSchemaValidator inputValidator;
    private final String systemPrompt;
    private final String responseSchema;

    public WeeklyReviewAiProviderRequestFactory(
            WeeklyReviewAiInputCompactor compactor,
            WeeklyReviewAiContentCodec codec
    ) {
        this.compactor = requireNonNull(compactor, "compactor");
        this.codec = requireNonNull(codec, "codec");
        mapper = JsonMapper.builder()
                .findAndAddModules()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build();
        writer = mapper.writer();
        inputValidator = new LlmJsonSchemaValidator(
                WeeklyReviewAiContract.INPUT_SCHEMA
        );
        systemPrompt = resource(WeeklyReviewAiContract.SYSTEM_PROMPT);
        responseSchema = jsonResource(WeeklyReviewAiContract.CONTENT_SCHEMA);
    }

    public PreparedWeeklyReviewAiRequest prepare(
            WeeklyReviewAiProviderRequestCommand command
    ) {
        WeeklyReviewAiProviderRequestCommand value = requireNonNull(
                command, "command"
        );
        UUID job = requireNonNull(value.jobId(), "jobId");
        PersistedWeeklyReviewSnapshot source = requireNonNull(
                value.snapshot(), "snapshot"
        );
        Instant timestamp = requireNonNull(value.now(), "now");
        Duration timeout = requireNonNull(value.callTimeout(), "callTimeout");
        Instant deadline = requireNonNull(value.jobDeadline(), "jobDeadline");
        require(!timeout.isZero() && !timeout.isNegative(),
                "callTimeout must be positive");
        require(deadline.isAfter(timestamp), "AI generation deadline has passed");

        WeeklyReviewAiInput input = compactor.compact(source.response());
        String inputJson = codec.canonical(input);
        validateInput(inputJson);
        Instant timeoutDeadline = timestamp.plus(timeout);
        Instant callDeadline = timeoutDeadline.isBefore(deadline)
                ? timeoutDeadline : deadline;
        LlmProviderRequest request = new LlmProviderRequest(
                job,
                requireText(value.providerCode(), "providerCode"),
                requireText(value.requestedModel(), "requestedModel"),
                prompt(value.retryViolationCodes()),
                inputJson,
                responseSchema,
                requireNonNull(value.temperature(), "temperature"),
                value.maxOutputTokens(),
                callDeadline
        );
        return new PreparedWeeklyReviewAiRequest(
                request,
                hash(new RequestHashMaterial(
                        request.providerCode(),
                        request.requestedModel(),
                        request.systemPrompt(),
                        request.inputJson(),
                        request.responseSchemaJson(),
                        request.temperature(),
                        request.maxOutputTokens()
                )),
                input,
                codec.hash(inputJson)
        );
    }

    private void validateInput(String inputJson) {
        List<StructuralValidationViolation> violations =
                inputValidator.validate(inputJson);
        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "Weekly review AI input violates packaged schema: "
                            + violations.size() + " violation(s)"
            );
        }
    }

    private String prompt(List<String> retryViolationCodes) {
        List<String> codes = List.copyOf(requireNonNull(
                retryViolationCodes, "retryViolationCodes"
        ));
        codes.forEach(code -> require(
                code.matches("[A-Z][A-Z0-9_]{2,80}"),
                "retry violation code is invalid"
        ));
        if (codes.isEmpty()) {
            return systemPrompt;
        }
        return systemPrompt + "\n\nПредыдущий ответ был отклонён проверками: "
                + String.join(", ", codes)
                + ". Исправь только перечисленные нарушения и снова верни точный JSON.";
    }

    private String jsonResource(String name) {
        try {
            return writer.writeValueAsString(mapper.readTree(resource(name)));
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Cannot canonicalize weekly review AI resource: " + name,
                    exception
            );
        }
    }

    private String resource(String name) {
        ClassLoader classLoader = getClass().getClassLoader();
        try (InputStream input = classLoader.getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Missing weekly review AI resource: " + name
                );
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Cannot read weekly review AI resource: " + name,
                    exception
            );
        }
    }

    private String hash(Object value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            writer.writeValueAsBytes(value)
                    )
            );
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Weekly review AI request hash cannot be encoded",
                    exception
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record RequestHashMaterial(
            String providerCode,
            String requestedModel,
            String systemPrompt,
            String inputJson,
            String responseSchemaJson,
            BigDecimal temperature,
            int maxOutputTokens
    ) {
    }
}
