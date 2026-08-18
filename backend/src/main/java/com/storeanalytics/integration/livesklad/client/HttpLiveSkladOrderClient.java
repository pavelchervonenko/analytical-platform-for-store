package com.storeanalytics.integration.livesklad.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.storeanalytics.common.config.LiveSkladPayloadLimitsProperties;
import com.storeanalytics.common.config.LiveSkladProperties;
import com.storeanalytics.integration.livesklad.dto.LiveSkladOrderDetailPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladOrderPositionPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladOrderSummaryPayload;
import com.storeanalytics.integration.livesklad.exception.LiveSkladException;
import com.storeanalytics.integration.livesklad.exception.LiveSkladHttpException;
import com.storeanalytics.integration.livesklad.exception.LiveSkladPayloadRejectedException;
import com.storeanalytics.integration.livesklad.exception.LiveSkladPayloadRejectedException.Reason;
import com.storeanalytics.integration.livesklad.exception.LiveSkladRateLimitException;
import com.storeanalytics.integration.livesklad.exception.LiveSkladTransportException;
import com.storeanalytics.integration.livesklad.observability.LiveSkladPayloadRejectionMetrics;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Service
public class HttpLiveSkladOrderClient implements LiveSkladOrderClient {

    private static final Duration TOKEN_REFRESH_SKEW = Duration.ofSeconds(30);
    private static final Duration MAX_TOKEN_CACHE_TTL = Duration.ofDays(1);
    private static final Duration DEFAULT_RETRY_AFTER = Duration.ofMinutes(15);
    private static final Duration MAX_RETRY_AFTER = Duration.ofDays(1);
    private static final int MAX_ACCESS_TOKEN_LENGTH = 4096;
    private static final int ORDER_PAGE_SIZE = 50;
    private static final int MAX_ORDER_PAGES = 200;

    private final LiveSkladProperties properties;
    private final LiveSkladPayloadLimitsProperties payloadLimits;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final LiveSkladRequestBudget requestBudget;
    private final LiveSkladPayloadRejectionMetrics rejectionMetrics;
    private final Clock clock;
    private volatile CachedToken cachedToken;

    HttpLiveSkladOrderClient(
            RestClient.Builder builder,
            LiveSkladProperties properties,
            ObjectMapper objectMapper
    ) {
        this(
                builder,
                properties,
                objectMapper,
                LiveSkladPayloadLimitsProperties.defaults(),
                Clock.systemUTC(),
                LiveSkladPayloadRejectionMetrics.noop()
        );
    }

    @Autowired
    public HttpLiveSkladOrderClient(
            RestClient.Builder builder,
            LiveSkladProperties properties,
            ObjectMapper objectMapper,
            LiveSkladPayloadLimitsProperties payloadLimits,
            Clock clock,
            LiveSkladPayloadRejectionMetrics rejectionMetrics
    ) {
        this.properties = properties;
        this.payloadLimits = payloadLimits;
        this.objectMapper = objectMapper;
        this.requestBudget = new LiveSkladRequestBudget(clock);
        this.rejectionMetrics = rejectionMetrics;
        this.clock = clock;
        this.restClient = buildRestClient(
                builder,
                properties,
                objectMapper,
                payloadLimits,
                rejectionMetrics
        );
    }

    @Override
    public List<LiveSkladOrderSummaryPayload> fetchOrders(
            Instant changedPeriodStart,
            Instant changedPeriodEnd
    ) {
        validatePeriod(changedPeriodStart, changedPeriodEnd);
        return executeAuthenticated(
                token -> requestOrders(
                        token,
                        changedPeriodStart,
                        changedPeriodEnd
                ),
                "LiveSklad orders request failed"
        );
    }

    @Override
    public LiveSkladOrderDetailPayload fetchOrderDetail(
            String orderExternalId
    ) {
        requireText(orderExternalId, "orderExternalId");
        return executeAuthenticated(
                token -> requestOrderDetail(token, orderExternalId),
                "LiveSklad order detail request failed"
        );
    }

    private List<LiveSkladOrderSummaryPayload> requestOrders(
            String token,
            Instant changedPeriodStart,
            Instant changedPeriodEnd
    ) {
        String dateRange = "[" + changedPeriodStart.toEpochMilli()
                + "," + changedPeriodEnd.toEpochMilli() + "]";
        List<LiveSkladOrderSummaryPayload> orders = new ArrayList<>();
        Set<String> orderIds = new HashSet<>();
        for (int page = 1; page <= MAX_ORDER_PAGES; page++) {
            int currentPage = page;
            OrdersEnvelope response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/company/orders")
                            .queryParam("lastAction", dateRange)
                            .queryParam("page", currentPage)
                            .queryParam("pageSize", ORDER_PAGE_SIZE)
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, token)
                    .retrieve()
                    .body(OrdersEnvelope.class);
            if (response == null || response.data() == null) {
                throw new LiveSkladException(
                        "LiveSklad orders response does not contain data"
                );
            }
            validateCollectionSize(response.data().size(), "orders");
            requestBudget.observe(response.remainRequest(), response.expireDate());
            for (JsonNode payload : response.data()) {
                OrderSummaryDto order = objectMapper.convertValue(
                        payload,
                        OrderSummaryDto.class
                );
                validateOrderSummary(order);
                if (!orderIds.add(order.id())) {
                    throw new LiveSkladException(
                            "LiveSklad orders response contains duplicate IDs"
                    );
                }
                orders.add(new LiveSkladOrderSummaryPayload(
                        order.id(),
                        order.number(),
                        order.dateCreate(),
                        Boolean.TRUE.equals(order.isVisible()),
                        order.status().id(),
                        order.status().name(),
                        order.shop().id(),
                        payload.deepCopy()
                ));
            }
            if (response.data().size() < ORDER_PAGE_SIZE
                    || response.total() != null
                    && orders.size() >= response.total()) {
                return List.copyOf(orders);
            }
        }
        throw new LiveSkladException(
                "LiveSklad orders pagination exceeded safety limit"
        );
    }

    private LiveSkladOrderDetailPayload requestOrderDetail(
            String token,
            String orderExternalId
    ) {
        OrderDetailEnvelope response = restClient.get()
                .uri("/orders/{orderId}", orderExternalId)
                .header(HttpHeaders.AUTHORIZATION, token)
                .retrieve()
                .body(OrderDetailEnvelope.class);
        if (response == null || response.data() == null) {
            throw new LiveSkladException(
                    "LiveSklad order detail response does not contain data"
            );
        }
        requestBudget.observe(response.remainRequest(), response.expireDate());
        OrderDetailDto detail = objectMapper.convertValue(
                response.data(),
                OrderDetailDto.class
        );
        validateOrderDetail(detail, orderExternalId);
        validatePositionCount(detail.positions().size());

        Set<String> positionIds = new HashSet<>();
        List<LiveSkladOrderPositionPayload> positions = new ArrayList<>();
        for (JsonNode payload : detail.positions()) {
            OrderPositionDto position = objectMapper.convertValue(
                    payload,
                    OrderPositionDto.class
            );
            validateOrderPosition(position);
            if (!positionIds.add(position.positionId())) {
                throw new LiveSkladException(
                        "LiveSklad order detail contains duplicate position IDs"
                );
            }
            CustomerDto employee = position.customer();
            positions.add(new LiveSkladOrderPositionPayload(
                    position.positionId(),
                    position.nomenclatureId(),
                    textValue(position.code()),
                    position.article(),
                    position.name(),
                    position.isWork(),
                    position.count(),
                    position.price(),
                    position.soldPrice(),
                    position.purchasePriceSumm(),
                    position.date(),
                    employee == null ? null : employee.id(),
                    employee == null ? null : employee.name(),
                    payload.deepCopy()
            ));
        }

        return new LiveSkladOrderDetailPayload(
                detail.id(),
                detail.number(),
                detail.dateCreate(),
                detail.lastAction(),
                detail.dateClose(),
                Boolean.TRUE.equals(detail.isVisible()),
                detail.status().id(),
                detail.status().name(),
                detail.shop().id(),
                List.copyOf(positions),
                response.data().deepCopy()
        );
    }

    private void validateOrderSummary(OrderSummaryDto order) {
        if (!StringUtils.hasText(order.id())
                || order.dateCreate() == null
                || order.isVisible() == null
                || !validRelation(order.status(), true)
                || !validRelation(order.shop(), false)) {
            throw new LiveSkladException(
                    "LiveSklad order summary is incomplete"
            );
        }
    }

    private void validateOrderDetail(
            OrderDetailDto detail,
            String requestedOrderExternalId
    ) {
        if (!StringUtils.hasText(detail.id())
                || !requestedOrderExternalId.equals(detail.id())
                || detail.dateCreate() == null
                || detail.lastAction() == null
                || detail.isVisible() == null
                || !validRelation(detail.status(), true)
                || !validRelation(detail.shop(), false)
                || detail.positions() == null) {
            throw new LiveSkladException(
                    "LiveSklad order detail is incomplete or inconsistent"
            );
        }
    }

    private boolean validRelation(RelationDto relation, boolean requireName) {
        return relation != null
                && StringUtils.hasText(relation.id())
                && (!requireName || StringUtils.hasText(relation.name()));
    }

    private void validateOrderPosition(OrderPositionDto position) {
        if (!StringUtils.hasText(position.positionId())
                || !StringUtils.hasText(position.nomenclatureId())
                || !StringUtils.hasText(position.name())
                || position.isWork() == null
                || position.date() == null) {
            throw new LiveSkladException(
                    "LiveSklad order position identity is incomplete"
            );
        }
        if (position.customer() != null
                && (!StringUtils.hasText(position.customer().id())
                || !StringUtils.hasText(position.customer().name()))) {
            throw new LiveSkladException(
                    "LiveSklad order position employee is incomplete"
            );
        }
        requireNonNegative(position.count(), "order position count", true);
        if (position.count().signum() == 0) {
            throw new LiveSkladException(
                    "LiveSklad order position count must be positive"
            );
        }
        requireNonNegative(position.price(), "order position price", true);
        requireNonNegative(
                position.soldPrice(),
                "order position soldPrice",
                true
        );
        requireNonNegative(
                position.purchasePriceSumm(),
                "order position purchasePriceSumm",
                false
        );
    }

    private void validatePeriod(Instant start, Instant end) {
        if (start == null || end == null || !end.isAfter(start)) {
            throw new IllegalArgumentException(
                    "order change period must contain an end after its start"
            );
        }
    }

    private void validateCollectionSize(int count, String resource) {
        if (count > payloadLimits.maxCollectionRecords()) {
            throw payloadRejected(
                    Reason.COLLECTION_RECORD_COUNT,
                    "LiveSklad " + resource
                            + " response exceeds the record count limit",
                    null
            );
        }
    }

    private void validatePositionCount(int count) {
        if (count > payloadLimits.maxPositionsPerDocument()) {
            throw payloadRejected(
                    Reason.DOCUMENT_POSITION_COUNT,
                    "LiveSklad order detail exceeds the position count limit",
                    null
            );
        }
    }

    private void requireNonNegative(
            BigDecimal value,
            String fieldName,
            boolean required
    ) {
        if (required && value == null) {
            throw new LiveSkladException(fieldName + " is required");
        }
        if (value != null && value.signum() < 0) {
            throw new LiveSkladException(fieldName + " must not be negative");
        }
    }

    private String textValue(JsonNode value) {
        return value == null || value.isNull() ? null : value.asString();
    }

    private void requireText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    private <T> T executeAuthenticated(
            TokenRequest<T> request,
            String failureMessage
    ) {
        String token = accessToken();
        try {
            return request.execute(token);
        } catch (RestClientResponseException exception) {
            if (!isAuthenticationFailure(exception)) {
                throw translate(failureMessage, exception);
            }
            invalidateToken(token);
            try {
                return request.execute(accessToken());
            } catch (RestClientResponseException retryException) {
                throw translate(
                        failureMessage + " after token refresh",
                        retryException
                );
            } catch (RestClientException retryException) {
                throw translate(
                        failureMessage + " after token refresh",
                        retryException
                );
            }
        } catch (RestClientException exception) {
            throw translate(failureMessage, exception);
        }
    }

    private String accessToken() {
        CachedToken current = cachedToken;
        if (current != null && current.isUsable(clock.instant())) {
            return current.value();
        }
        synchronized (this) {
            current = cachedToken;
            if (current == null || !current.isUsable(clock.instant())) {
                cachedToken = authenticate();
            }
            return cachedToken.value();
        }
    }

    private CachedToken authenticate() {
        requireCredentials();
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("login", properties.login());
        form.add("password", properties.password());
        try {
            AuthResponse response = restClient.post()
                    .uri("/auth")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(AuthResponse.class);
            if (response == null
                    || !StringUtils.hasText(response.token())
                    || response.token().length() > MAX_ACCESS_TOKEN_LENGTH
                    || response.token().indexOf('\r') >= 0
                    || response.token().indexOf('\n') >= 0
                    || response.ttl() <= 0) {
                throw new LiveSkladException(
                        "LiveSklad authentication response is invalid"
                );
            }
            requestBudget.observe(response.remainRequest(), response.expireDate());
            Duration ttl = Duration.ofSeconds(
                    Math.min(response.ttl(), MAX_TOKEN_CACHE_TTL.toSeconds())
            );
            Duration effectiveSkew = ttl.compareTo(TOKEN_REFRESH_SKEW) > 0
                    ? TOKEN_REFRESH_SKEW : ttl.dividedBy(2);
            return new CachedToken(
                    response.token(),
                    clock.instant().plus(ttl).minus(effectiveSkew)
            );
        } catch (RestClientResponseException exception) {
            throw translate("LiveSklad authentication failed", exception);
        } catch (RestClientException exception) {
            throw translate("LiveSklad authentication failed", exception);
        }
    }

    private void requireCredentials() {
        if (!StringUtils.hasText(properties.baseUrl())) {
            throw new LiveSkladException(
                    "LiveSklad API base URL is not configured"
            );
        }
        if (!StringUtils.hasText(properties.login())
                || !StringUtils.hasText(properties.password())) {
            throw new LiveSkladException(
                    "LiveSklad API credentials are not configured"
            );
        }
    }

    private void invalidateToken(String token) {
        synchronized (this) {
            if (cachedToken != null && cachedToken.value().equals(token)) {
                cachedToken = null;
            }
        }
    }

    private boolean isAuthenticationFailure(
            RestClientResponseException exception
    ) {
        int status = exception.getStatusCode().value();
        return status == 401 || status == 403;
    }

    private LiveSkladException translate(
            String message,
            RestClientResponseException exception
    ) {
        int status = exception.getStatusCode().value();
        if (status == 429) {
            return new LiveSkladRateLimitException(message, retryAfter(exception));
        }
        return new LiveSkladHttpException(message, status);
    }

    private LiveSkladException translate(
            String message,
            RestClientException exception
    ) {
        LiveSkladPayloadRejectedException rejected = findCause(
                exception,
                LiveSkladPayloadRejectedException.class
        );
        if (rejected != null) {
            return rejected;
        }
        if (findCause(
                exception,
                LiveSkladResponseGuard.ResponseSizeLimitIOException.class
        ) != null) {
            return payloadRejected(
                    Reason.RESPONSE_TOO_LARGE,
                    message + ": response exceeds the configured byte limit",
                    exception
            );
        }
        if (findCause(exception, StreamConstraintsException.class) != null) {
            return payloadRejected(
                    Reason.JSON_COMPLEXITY,
                    message + ": JSON exceeds the configured complexity limit",
                    exception
            );
        }
        return new LiveSkladTransportException(message, exception);
    }

    private Duration retryAfter(RestClientResponseException exception) {
        HttpHeaders headers = exception.getResponseHeaders();
        String value = headers == null
                ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (!StringUtils.hasText(value)) {
            return DEFAULT_RETRY_AFTER;
        }
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds < 0
                    ? DEFAULT_RETRY_AFTER
                    : boundRetryAfter(Duration.ofSeconds(seconds));
        } catch (NumberFormatException ignored) {
            // Retry-After may instead contain an RFC 1123 timestamp.
        }
        try {
            Instant retryAt = ZonedDateTime.parse(
                    value.trim(),
                    DateTimeFormatter.RFC_1123_DATE_TIME
            ).toInstant();
            Duration delay = Duration.between(clock.instant(), retryAt);
            return boundRetryAfter(delay.isNegative() ? Duration.ZERO : delay);
        } catch (DateTimeParseException ignored) {
            return DEFAULT_RETRY_AFTER;
        }
    }

    private Duration boundRetryAfter(Duration candidate) {
        return candidate.compareTo(MAX_RETRY_AFTER) > 0
                ? MAX_RETRY_AFTER : candidate;
    }

    private LiveSkladPayloadRejectedException payloadRejected(
            Reason reason,
            String message,
            Throwable cause
    ) {
        rejectionMetrics.record(reason);
        return new LiveSkladPayloadRejectedException(reason, message, cause);
    }

    private <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    private RestClient buildRestClient(
            RestClient.Builder builder,
            LiveSkladProperties clientProperties,
            ObjectMapper applicationObjectMapper,
            LiveSkladPayloadLimitsProperties limits,
            LiveSkladPayloadRejectionMetrics metrics
    ) {
        JsonMapper liveSkladObjectMapper = constrainedObjectMapper(
                applicationObjectMapper,
                limits
        );
        RestClient.Builder configuredBuilder = builder.clone()
                .configureMessageConverters(converters ->
                        converters.withJsonConverter(
                                new JacksonJsonHttpMessageConverter(
                                        liveSkladObjectMapper
                                )
                        )
                )
                .defaultHeader(HttpHeaders.ACCEPT_ENCODING, "identity")
                .requestInterceptor(new LiveSkladResponseGuard(
                        limits.maxResponseBytes(),
                        metrics
                ));
        if (!StringUtils.hasText(clientProperties.baseUrl())) {
            return configuredBuilder.build();
        }
        SimpleClientHttpRequestFactory requestFactory =
                new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(clientProperties.connectTimeout());
        requestFactory.setReadTimeout(clientProperties.readTimeout());
        return configuredBuilder
                .baseUrl(stripTrailingSlash(clientProperties.baseUrl()))
                .requestFactory(requestFactory)
                .requestInterceptor((request, body, execution) -> {
                    requestBudget.beforeRequest();
                    return execution.execute(request, body);
                })
                .build();
    }

    private JsonMapper constrainedObjectMapper(
            ObjectMapper applicationObjectMapper,
            LiveSkladPayloadLimitsProperties limits
    ) {
        JsonFactory jsonFactory = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxDocumentLength(limits.maxDocumentLength())
                        .maxTokenCount(limits.maxTokenCount())
                        .maxNestingDepth(limits.maxNestingDepth())
                        .maxStringLength(limits.maxStringLength())
                        .maxNameLength(limits.maxNameLength())
                        .maxNumberLength(limits.maxNumberLength())
                        .build())
                .build();
        return JsonMapper.builder(jsonFactory)
                .addModules(applicationObjectMapper.registeredModules())
                .build();
    }

    private String stripTrailingSlash(String baseUrl) {
        return baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AuthResponse(
            String token,
            long ttl,
            Integer remainRequest,
            Instant expireDate
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OrdersEnvelope(
            List<JsonNode> data,
            Integer total,
            Integer remainRequest,
            Instant expireDate
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OrderSummaryDto(
            String id,
            String number,
            Instant dateCreate,
            Boolean isVisible,
            RelationDto status,
            RelationDto shop
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OrderDetailEnvelope(
            JsonNode data,
            Integer remainRequest,
            Instant expireDate
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OrderDetailDto(
            String id,
            String number,
            Instant dateCreate,
            Instant lastAction,
            Instant dateClose,
            Boolean isVisible,
            RelationDto status,
            RelationDto shop,
            List<JsonNode> positions
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OrderPositionDto(
            String positionId,
            String nomenclatureId,
            JsonNode code,
            String article,
            String name,
            Boolean isWork,
            BigDecimal count,
            BigDecimal price,
            BigDecimal soldPrice,
            BigDecimal purchasePriceSumm,
            Instant date,
            CustomerDto customer
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RelationDto(String id, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CustomerDto(String id, String name) {
    }

    @FunctionalInterface
    private interface TokenRequest<T> {

        T execute(String token);
    }

    private record CachedToken(String value, Instant refreshAt) {

        private boolean isUsable(Instant now) {
            return now.isBefore(refreshAt);
        }
    }
}
