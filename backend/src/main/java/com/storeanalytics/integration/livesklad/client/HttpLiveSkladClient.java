package com.storeanalytics.integration.livesklad.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeanalytics.common.config.LiveSkladProperties;
import com.storeanalytics.integration.livesklad.dto.LiveSkladCashItemPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladCashRegisterPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladCashTransactionPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladEmployeePayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladReturnDetailPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladReturnPositionPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladSaleDetailPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladSalePositionPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladSaleSummaryPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladStorePayload;
import com.storeanalytics.integration.livesklad.exception.LiveSkladException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Service
public class HttpLiveSkladClient implements LiveSkladClient {

    private static final Duration TOKEN_REFRESH_SKEW = Duration.ofSeconds(30);
    private static final int EMPLOYEE_PAGE_SIZE = 50;
    private static final int MAX_EMPLOYEE_PAGES = 100;
    private static final int SALES_PAGE_SIZE = 50;
    private static final int MAX_SALES_PAGES = 200;

    private final LiveSkladProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private static final int CASH_PAGE_SIZE = 50;
    private static final int MAX_CASH_PAGES = 200;
    private volatile CachedToken cachedToken;

    public HttpLiveSkladClient(
            RestClient.Builder builder,
            LiveSkladProperties properties,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = buildRestClient(builder, properties);
    }

    @Override
    public List<LiveSkladStorePayload> fetchStores() {
        String token = accessToken();
        try {
            return requestStores(token);
        } catch (RestClientResponseException exception) {
            if (!isAuthenticationFailure(exception)) {
                throw translate("LiveSklad stores request failed", exception);
            }
            invalidateToken(token);
            return retryStoresAfterAuthenticationFailure();
        } catch (RestClientException exception) {
            throw new LiveSkladException("LiveSklad stores request failed", exception);
        }
    }

    @Override
    public List<LiveSkladEmployeePayload> fetchEmployees(String storeExternalId) {
        if (!StringUtils.hasText(storeExternalId)) {
            throw new IllegalArgumentException("storeExternalId must not be blank");
        }
        String token = accessToken();
        try {
            return requestEmployees(token, storeExternalId);
        } catch (RestClientResponseException exception) {
            if (!isAuthenticationFailure(exception)) {
                throw translate("LiveSklad employees request failed", exception);
            }
            invalidateToken(token);
            return retryEmployeesAfterAuthenticationFailure(storeExternalId);
        } catch (RestClientException exception) {
            throw new LiveSkladException("LiveSklad employees request failed", exception);
        }
    }

    @Override
    public List<LiveSkladSaleSummaryPayload> fetchSales(
            String storeExternalId,
            Instant periodStart,
            Instant periodEnd
    ) {
        validateSalesRequest(storeExternalId, periodStart, periodEnd);
        String token = accessToken();
        try {
            return requestSales(token, storeExternalId, periodStart, periodEnd);
        } catch (RestClientResponseException exception) {
            if (!isAuthenticationFailure(exception)) {
                throw translate("LiveSklad sales request failed", exception);
            }
            invalidateToken(token);
            return retrySalesAfterAuthenticationFailure(
                    storeExternalId,
                    periodStart,
                    periodEnd
            );
        } catch (RestClientException exception) {
            throw new LiveSkladException("LiveSklad sales request failed", exception);
        }
    }

    @Override
    public LiveSkladSaleDetailPayload fetchSaleDetail(String saleExternalId) {
        if (!StringUtils.hasText(saleExternalId)) {
            throw new IllegalArgumentException("saleExternalId must not be blank");
        }
        String token = accessToken();
        try {
            return requestSaleDetail(token, saleExternalId);
        } catch (RestClientResponseException exception) {
            if (!isAuthenticationFailure(exception)) {
                throw translate("LiveSklad sale detail request failed", exception);
            }
            invalidateToken(token);
            return retrySaleDetailAfterAuthenticationFailure(saleExternalId);
        } catch (RestClientException exception) {
            throw new LiveSkladException("LiveSklad sale detail request failed", exception);
        }
    }

    @Override
    public List<LiveSkladCashItemPayload> fetchCashItems() {
        return executeAuthenticated(
                this::requestCashItems,
                "LiveSklad cash items request failed"
        );
    }

    @Override
    public List<LiveSkladCashRegisterPayload> fetchCashRegisters(
            String storeExternalId
    ) {
        requireText(storeExternalId, "storeExternalId");
        return executeAuthenticated(
                token -> requestCashRegisters(token, storeExternalId),
                "LiveSklad cash registers request failed"
        );
    }

    @Override
    public List<LiveSkladCashTransactionPayload> fetchCashTransactions(
            String cashRegisterExternalId,
            String cashItemExternalId,
            Instant periodStart,
            Instant periodEnd
    ) {
        requireText(cashRegisterExternalId, "cashRegisterExternalId");
        requireText(cashItemExternalId, "cashItemExternalId");
        validatePeriod(periodStart, periodEnd, "cash transaction");
        return executeAuthenticated(
                token -> requestCashTransactions(
                        token,
                        cashRegisterExternalId,
                        cashItemExternalId,
                        periodStart,
                        periodEnd
                ),
                "LiveSklad cash transactions request failed"
        );
    }

    @Override
    public LiveSkladReturnDetailPayload fetchReturnDetail(
            String returnExternalId
    ) {
        requireText(returnExternalId, "returnExternalId");
        return executeAuthenticated(
                token -> requestReturnDetail(token, returnExternalId),
                "LiveSklad return detail request failed"
        );
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
                throw new LiveSkladException(
                        failureMessage + " after token refresh",
                        retryException
                );
            }
        } catch (RestClientException exception) {
            throw new LiveSkladException(failureMessage, exception);
        }
    }

    private List<LiveSkladSaleSummaryPayload> retrySalesAfterAuthenticationFailure(
            String storeExternalId,
            Instant periodStart,
            Instant periodEnd
    ) {
        try {
            return requestSales(accessToken(), storeExternalId, periodStart, periodEnd);
        } catch (RestClientResponseException exception) {
            throw translate(
                    "LiveSklad sales request failed after token refresh",
                    exception
            );
        } catch (RestClientException exception) {
            throw new LiveSkladException(
                    "LiveSklad sales request failed after token refresh",
                    exception
            );
        }
    }

    private LiveSkladSaleDetailPayload retrySaleDetailAfterAuthenticationFailure(
            String saleExternalId
    ) {
        try {
            return requestSaleDetail(accessToken(), saleExternalId);
        } catch (RestClientResponseException exception) {
            throw translate(
                    "LiveSklad sale detail request failed after token refresh",
                    exception
            );
        } catch (RestClientException exception) {
            throw new LiveSkladException(
                    "LiveSklad sale detail request failed after token refresh",
                    exception
            );
        }
    }

    private List<LiveSkladSaleSummaryPayload> requestSales(
            String token,
            String storeExternalId,
            Instant periodStart,
            Instant periodEnd
    ) {
        String dateRange = "[" + periodStart.toEpochMilli()
                + "," + periodEnd.toEpochMilli() + "]";
        List<LiveSkladSaleSummaryPayload> sales = new ArrayList<>();
        Set<String> saleIds = new HashSet<>();
        for (int page = 1; page <= MAX_SALES_PAGES; page++) {
            int currentPage = page;
            SalesEnvelope response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/shops/{storeId}/sales")
                            .queryParam("date", dateRange)
                            .queryParam("page", currentPage)
                            .queryParam("pageSize", SALES_PAGE_SIZE)
                            .queryParam("sort", "date ASC")
                            .build(storeExternalId))
                    .header(HttpHeaders.AUTHORIZATION, token)
                    .retrieve()
                    .body(SalesEnvelope.class);
            if (response == null || response.data() == null) {
                throw new LiveSkladException(
                        "LiveSklad sales response does not contain data"
                );
            }

            for (JsonNode payload : response.data()) {
                SaleSummaryDto sale = objectMapper.convertValue(
                        payload,
                        SaleSummaryDto.class
                );
                validateSaleSummary(sale);
                if (!saleIds.add(sale.id())) {
                    throw new LiveSkladException(
                            "LiveSklad sales response contains duplicate IDs"
                    );
                }
                sales.add(new LiveSkladSaleSummaryPayload(
                        sale.id(),
                        sale.number(),
                        sale.date(),
                        sale.type(),
                        sale.summ().price(),
                        sale.summ().soldPrice(),
                        sale.summ().purchasePrice(),
                        payload.deepCopy()
                ));
            }
            if (response.data().size() < SALES_PAGE_SIZE
                    || response.total() != null && sales.size() >= response.total()) {
                return List.copyOf(sales);
            }
        }
        throw new LiveSkladException("LiveSklad sales pagination exceeded safety limit");
    }

    private LiveSkladSaleDetailPayload requestSaleDetail(
            String token,
            String saleExternalId
    ) {
        SaleDetailEnvelope response = restClient.get()
                .uri("/documents/{saleId}", saleExternalId)
                .header(HttpHeaders.AUTHORIZATION, token)
                .retrieve()
                .body(SaleDetailEnvelope.class);
        if (response == null || response.data() == null) {
            throw new LiveSkladException(
                    "LiveSklad sale detail response does not contain data"
            );
        }

        SaleDetailDto detail = objectMapper.convertValue(
                response.data(),
                SaleDetailDto.class
        );
        validateSaleDetail(detail);
        Set<String> positionIds = new HashSet<>();
        List<LiveSkladSalePositionPayload> positions = new ArrayList<>();
        for (JsonNode payload : detail.positions()) {
            SalePositionDto position = objectMapper.convertValue(
                    payload,
                    SalePositionDto.class
            );
            validateSalePosition(position);
            if (!positionIds.add(position.positionId())) {
                throw new LiveSkladException(
                        "LiveSklad sale detail contains duplicate position IDs"
                );
            }
            positions.add(new LiveSkladSalePositionPayload(
                    position.positionId(),
                    position.nomenclatureId(),
                    textValue(position.code()),
                    position.article(),
                    position.name(),
                    position.isWork(),
                    position.count(),
                    position.price(),
                    position.soldPrice(),
                    position.purchasePriceSumm()
            ));
        }

        CustomerDto customer = detail.customer();
        return new LiveSkladSaleDetailPayload(
                detail.id(),
                detail.number(),
                detail.date(),
                detail.dateChange(),
                detail.type(),
                detail.shop().id(),
                customer == null ? null : customer.id(),
                customer == null ? null : customer.name(),
                amountOrZero(detail.cash().money()),
                amountOrZero(detail.cash().bank()),
                amountOrZero(detail.cash().invoice()),
                List.copyOf(positions),
                response.data().deepCopy()
        );
    }

    private List<LiveSkladCashItemPayload> requestCashItems(String token) {
        JsonNode response = restClient.get()
                .uri("/cash-items")
                .header(HttpHeaders.AUTHORIZATION, token)
                .retrieve()
                .body(JsonNode.class);
        List<JsonNode> payloads = collectionData(response, "cash items");
        List<LiveSkladCashItemPayload> items = new ArrayList<>();
        Set<String> itemIds = new HashSet<>();
        for (JsonNode payload : payloads) {
            CashItemDto item = objectMapper.convertValue(payload, CashItemDto.class);
            validateCashItem(item);
            if (!itemIds.add(item.id())) {
                throw new LiveSkladException(
                        "LiveSklad cash items response contains duplicate IDs"
                );
            }
            items.add(new LiveSkladCashItemPayload(
                    item.id(),
                    item.name(),
                    item.type(),
                    item.isIncome(),
                    Boolean.TRUE.equals(item.isBalance()),
                    payload.deepCopy()
            ));
        }
        return List.copyOf(items);
    }

    private List<LiveSkladCashRegisterPayload> requestCashRegisters(
            String token,
            String storeExternalId
    ) {
        CashRegistersEnvelope response = restClient.get()
                .uri("/shops/{storeId}/cash-registers", storeExternalId)
                .header(HttpHeaders.AUTHORIZATION, token)
                .retrieve()
                .body(CashRegistersEnvelope.class);
        if (response == null || response.data() == null) {
            throw new LiveSkladException(
                    "LiveSklad cash registers response does not contain data"
            );
        }
        List<LiveSkladCashRegisterPayload> registers = new ArrayList<>();
        Set<String> registerIds = new HashSet<>();
        for (JsonNode payload : response.data()) {
            CashRegisterDto register = objectMapper.convertValue(
                    payload,
                    CashRegisterDto.class
            );
            validateCashRegister(register, storeExternalId);
            if (!registerIds.add(register.id())) {
                throw new LiveSkladException(
                        "LiveSklad cash registers response contains duplicate IDs"
                );
            }
            registers.add(new LiveSkladCashRegisterPayload(
                    register.id(),
                    register.name(),
                    register.shopId(),
                    payload.deepCopy()
            ));
        }
        return List.copyOf(registers);
    }

    private List<LiveSkladCashTransactionPayload> requestCashTransactions(
            String token,
            String cashRegisterExternalId,
            String cashItemExternalId,
            Instant periodStart,
            Instant periodEnd
    ) {
        String dateRange = "[" + periodStart.toEpochMilli()
                + "," + periodEnd.toEpochMilli() + "]";
        List<LiveSkladCashTransactionPayload> transactions = new ArrayList<>();
        Set<String> transactionIds = new HashSet<>();
        for (int page = 1; page <= MAX_CASH_PAGES; page++) {
            int currentPage = page;
            CashTransactionsEnvelope response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/cash-registers/{registerId}/cash")
                            .queryParam("date", dateRange)
                            .queryParam("cashItemId", cashItemExternalId)
                            .queryParam("page", currentPage)
                            .queryParam("pageSize", CASH_PAGE_SIZE)
                            .queryParam("sort", "date ASC")
                            .build(cashRegisterExternalId))
                    .header(HttpHeaders.AUTHORIZATION, token)
                    .retrieve()
                    .body(CashTransactionsEnvelope.class);
            if (response == null || response.data() == null) {
                throw new LiveSkladException(
                        "LiveSklad cash transactions response does not contain data"
                );
            }
            for (JsonNode payload : response.data()) {
                CashTransactionDto transaction = objectMapper.convertValue(
                        payload,
                        CashTransactionDto.class
                );
                validateCashTransaction(
                        transaction,
                        cashRegisterExternalId,
                        cashItemExternalId
                );
                if (!transactionIds.add(transaction.id())) {
                    throw new LiveSkladException(
                            "LiveSklad cash transactions contain duplicate IDs"
                    );
                }
                transactions.add(toCashTransactionPayload(transaction, payload));
            }
            if (response.data().size() < CASH_PAGE_SIZE
                    || response.total() != null
                    && transactions.size() >= response.total()) {
                return List.copyOf(transactions);
            }
        }
        throw new LiveSkladException(
                "LiveSklad cash transactions pagination exceeded safety limit"
        );
    }

    private LiveSkladCashTransactionPayload toCashTransactionPayload(
            CashTransactionDto transaction,
            JsonNode payload
    ) {
        return new LiveSkladCashTransactionPayload(
                transaction.id(),
                transaction.date(),
                transaction.dateChange(),
                transaction.type(),
                transaction.shopId(),
                transaction.cashRegister().id(),
                transaction.cashItem().id(),
                transaction.cashItem().type(),
                transaction.cashItem().isIncome(),
                Boolean.TRUE.equals(transaction.isBalance()),
                Boolean.TRUE.equals(transaction.isBankTransfer()),
                transaction.money(),
                relationId(transaction.customer()),
                relationId(transaction.worker()),
                relationId(transaction.document()),
                payload.deepCopy()
        );
    }

    private LiveSkladReturnDetailPayload requestReturnDetail(
            String token,
            String returnExternalId
    ) {
        SaleDetailEnvelope response = restClient.get()
                .uri("/documents/{returnId}", returnExternalId)
                .header(HttpHeaders.AUTHORIZATION, token)
                .retrieve()
                .body(SaleDetailEnvelope.class);
        if (response == null || response.data() == null) {
            throw new LiveSkladException(
                    "LiveSklad return detail response does not contain data"
            );
        }
        ReturnDetailDto detail = objectMapper.convertValue(
                response.data(),
                ReturnDetailDto.class
        );
        validateReturnDetail(detail, returnExternalId);
        Set<String> positionIds = new HashSet<>();
        List<LiveSkladReturnPositionPayload> positions = new ArrayList<>();
        for (JsonNode payload : detail.positions()) {
            ReturnPositionDto position = objectMapper.convertValue(
                    payload,
                    ReturnPositionDto.class
            );
            validateReturnPosition(position);
            if (!positionIds.add(position.positionId())) {
                throw new LiveSkladException(
                        "LiveSklad return detail contains duplicate position IDs"
                );
            }
            positions.add(new LiveSkladReturnPositionPayload(
                    position.positionId(),
                    position.salePositionId(),
                    position.nomenclatureId(),
                    textValue(position.code()),
                    position.article(),
                    position.name(),
                    position.isWork(),
                    position.count(),
                    position.price(),
                    position.soldPrice(),
                    position.purchasePriceSumm()
            ));
        }
        return new LiveSkladReturnDetailPayload(
                detail.id(),
                detail.number(),
                detail.date(),
                detail.dateChange(),
                detail.type(),
                detail.shop().id(),
                relationId(detail.customer()),
                detail.parentDocument().id(),
                amountOrZero(detail.cash().money()),
                amountOrZero(detail.cash().bank()),
                amountOrZero(detail.cash().invoice()),
                List.copyOf(positions),
                response.data().deepCopy()
        );
    }

    private List<JsonNode> collectionData(JsonNode response, String resource) {
        if (response == null || response.isNull()) {
            throw new LiveSkladException(
                    "LiveSklad " + resource + " response is empty"
            );
        }
        JsonNode data = response.isArray() ? response : response.get("data");
        if (data == null || !data.isArray()) {
            throw new LiveSkladException(
                    "LiveSklad " + resource + " response does not contain data"
            );
        }
        List<JsonNode> result = new ArrayList<>();
        data.forEach(result::add);
        return result;
    }

    private String relationId(RelationDto relation) {
        return relation == null ? null : relation.id();
    }

    private String textValue(JsonNode value) {
        return value == null || value.isNull() ? null : value.asText();
    }

    private List<LiveSkladEmployeePayload> retryEmployeesAfterAuthenticationFailure(
            String storeExternalId
    ) {
        try {
            return requestEmployees(accessToken(), storeExternalId);
        } catch (RestClientResponseException exception) {
            throw translate(
                    "LiveSklad employees request failed after token refresh",
                    exception
            );
        } catch (RestClientException exception) {
            throw new LiveSkladException(
                    "LiveSklad employees request failed after token refresh",
                    exception
            );
        }
    }

    private List<LiveSkladEmployeePayload> requestEmployees(
            String token,
            String storeExternalId
    ) {
        List<LiveSkladEmployeePayload> employees = new ArrayList<>();
        Set<String> employeeIds = new HashSet<>();
        for (int page = 1; page <= MAX_EMPLOYEE_PAGES; page++) {
            int currentPage = page;
            EmployeesEnvelope response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/shops/{storeId}/customers")
                            .queryParam("page", currentPage)
                            .queryParam("pageSize", EMPLOYEE_PAGE_SIZE)
                            .build(storeExternalId))
                    .header(HttpHeaders.AUTHORIZATION, token)
                    .retrieve()
                    .body(EmployeesEnvelope.class);
            if (response == null || response.data() == null) {
                throw new LiveSkladException(
                        "LiveSklad employees response does not contain data"
                );
            }

            for (JsonNode payload : response.data()) {
                EmployeeDto employee = objectMapper.convertValue(payload, EmployeeDto.class);
                validateEmployee(employee);
                if (!employeeIds.add(employee.id())) {
                    throw new LiveSkladException(
                            "LiveSklad employees response contains duplicate IDs"
                    );
                }
                employees.add(new LiveSkladEmployeePayload(
                        employee.id(), employee.name(), payload.deepCopy()
                ));
            }
            if (response.data().size() < EMPLOYEE_PAGE_SIZE) {
                return List.copyOf(employees);
            }
        }
        throw new LiveSkladException("LiveSklad employees pagination exceeded safety limit");
    }

    private List<LiveSkladStorePayload> retryStoresAfterAuthenticationFailure() {
        try {
            return requestStores(accessToken());
        } catch (RestClientResponseException exception) {
            throw translate("LiveSklad stores request failed after token refresh", exception);
        } catch (RestClientException exception) {
            throw new LiveSkladException("LiveSklad stores request failed after token refresh", exception);
        }
    }

    private List<LiveSkladStorePayload> requestStores(String token) {
        StoresEnvelope response = restClient.get()
                .uri("/shops")
                .header(HttpHeaders.AUTHORIZATION, token)
                .retrieve()
                .body(StoresEnvelope.class);
        if (response == null || response.data() == null) {
            throw new LiveSkladException("LiveSklad stores response does not contain data");
        }

        List<LiveSkladStorePayload> stores = new ArrayList<>();
        for (JsonNode payload : response.data()) {
            StoreDto store = objectMapper.convertValue(payload, StoreDto.class);
            validateStore(store);
            stores.add(new LiveSkladStorePayload(
                    store.id(), store.name(), store.address(), store.color(), payload.deepCopy()
            ));
        }
        return List.copyOf(stores);
    }

    private String accessToken() {
        CachedToken current = cachedToken;
        if (current != null && current.isUsable()) {
            return current.value();
        }
        synchronized (this) {
            current = cachedToken;
            if (current == null || !current.isUsable()) {
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
            if (response == null || !StringUtils.hasText(response.token()) || response.ttl() <= 0) {
                throw new LiveSkladException("LiveSklad authentication response is invalid");
            }
            Duration ttl = Duration.ofSeconds(response.ttl());
            Duration effectiveSkew = ttl.compareTo(TOKEN_REFRESH_SKEW) > 0
                    ? TOKEN_REFRESH_SKEW : ttl.dividedBy(2);
            return new CachedToken(response.token(), Instant.now().plus(ttl).minus(effectiveSkew));
        } catch (RestClientResponseException exception) {
            throw translate("LiveSklad authentication failed", exception);
        } catch (RestClientException exception) {
            throw new LiveSkladException("LiveSklad authentication failed", exception);
        }
    }

    private void requireCredentials() {
        if (!StringUtils.hasText(properties.baseUrl())) {
            throw new LiveSkladException("LiveSklad API base URL is not configured");
        }
        if (!StringUtils.hasText(properties.login()) || !StringUtils.hasText(properties.password())) {
            throw new LiveSkladException("LiveSklad API credentials are not configured");
        }
    }

    private void invalidateToken(String token) {
        synchronized (this) {
            if (cachedToken != null && cachedToken.value().equals(token)) {
                cachedToken = null;
            }
        }
    }

    private boolean isAuthenticationFailure(RestClientResponseException exception) {
        int status = exception.getStatusCode().value();
        return status == 401 || status == 403;
    }

    private LiveSkladException translate(String message, RestClientResponseException exception) {
        int status = exception.getStatusCode().value();
        if (status == 429) {
            return new LiveSkladException(message + ": request limit exceeded");
        }
        return new LiveSkladException(message + ": HTTP " + status);
    }

    private void requireText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    private void validatePeriod(
            Instant periodStart,
            Instant periodEnd,
            String resource
    ) {
        if (periodStart == null
                || periodEnd == null
                || !periodEnd.isAfter(periodStart)) {
            throw new IllegalArgumentException(
                    resource + " period must contain an end after its start"
            );
        }
    }

    private void validateCashItem(CashItemDto item) {
        if (!StringUtils.hasText(item.id())
                || !StringUtils.hasText(item.name())
                || !StringUtils.hasText(item.type())
                || item.isIncome() == null) {
            throw new LiveSkladException(
                    "LiveSklad cash item identity is incomplete"
            );
        }
    }

    private void validateCashRegister(
            CashRegisterDto register,
            String requestedStoreExternalId
    ) {
        if (!StringUtils.hasText(register.id())
                || !StringUtils.hasText(register.name())
                || !StringUtils.hasText(register.shopId())) {
            throw new LiveSkladException(
                    "LiveSklad cash register identity is incomplete"
            );
        }
        if (!requestedStoreExternalId.equals(register.shopId())) {
            throw new LiveSkladException(
                    "LiveSklad cash register belongs to another store"
            );
        }
    }

    private void validateCashTransaction(
            CashTransactionDto transaction,
            String requestedRegisterExternalId,
            String requestedCashItemExternalId
    ) {
        if (!StringUtils.hasText(transaction.id())
                || transaction.date() == null
                || !StringUtils.hasText(transaction.type())
                || !StringUtils.hasText(transaction.shopId())
                || transaction.cashRegister() == null
                || !StringUtils.hasText(transaction.cashRegister().id())
                || transaction.cashItem() == null
                || !StringUtils.hasText(transaction.cashItem().id())
                || !StringUtils.hasText(transaction.cashItem().type())
                || transaction.cashItem().isIncome() == null
                || transaction.document() == null
                || !StringUtils.hasText(transaction.document().id())) {
            throw new LiveSkladException(
                    "LiveSklad cash transaction is incomplete"
            );
        }
        if (!requestedRegisterExternalId.equals(
                transaction.cashRegister().id()
        )) {
            throw new LiveSkladException(
                    "LiveSklad cash transaction belongs to another register"
            );
        }
        if (!requestedCashItemExternalId.equals(transaction.cashItem().id())) {
            throw new LiveSkladException(
                    "LiveSklad cash transaction belongs to another cash item"
            );
        }
        requireNonNegative(
                transaction.money(),
                "cash transaction money",
                true
        );
    }

    private void validateReturnDetail(
            ReturnDetailDto detail,
            String requestedReturnExternalId
    ) {
        if (!StringUtils.hasText(detail.id())
                || !requestedReturnExternalId.equals(detail.id())
                || detail.date() == null
                || !"saleReturn".equals(detail.type())
                || detail.shop() == null
                || !StringUtils.hasText(detail.shop().id())
                || detail.parentDocument() == null
                || !StringUtils.hasText(detail.parentDocument().id())
                || detail.cash() == null
                || detail.positions() == null) {
            throw new LiveSkladException(
                    "LiveSklad return detail is incomplete or inconsistent"
            );
        }
        requireNonNegative(detail.cash().money(), "return cash money", false);
        requireNonNegative(detail.cash().bank(), "return cash bank", false);
        requireNonNegative(detail.cash().invoice(), "return cash invoice", false);
    }

    private void validateReturnPosition(ReturnPositionDto position) {
        if (!StringUtils.hasText(position.positionId())
                || !StringUtils.hasText(position.salePositionId())
                || !StringUtils.hasText(position.nomenclatureId())
                || !StringUtils.hasText(position.name())
                || position.isWork() == null) {
            throw new LiveSkladException(
                    "LiveSklad return position identity is incomplete"
            );
        }
        requireNonNegative(position.count(), "return position count", true);
        if (position.count().signum() == 0) {
            throw new LiveSkladException(
                    "LiveSklad return position count must be positive"
            );
        }
        requireNonNegative(position.price(), "return position price", true);
        requireNonNegative(
                position.soldPrice(),
                "return position soldPrice",
                true
        );
        requireNonNegative(
                position.purchasePriceSumm(),
                "return position purchasePriceSumm",
                false
        );
    }

    private void validateStore(StoreDto store) {
        if (!StringUtils.hasText(store.id()) || !StringUtils.hasText(store.name())) {
            throw new LiveSkladException("LiveSklad store must contain id and name");
        }
    }

    private void validateEmployee(EmployeeDto employee) {
        if (!StringUtils.hasText(employee.id()) || !StringUtils.hasText(employee.name())) {
            throw new LiveSkladException("LiveSklad employee must contain id and name");
        }
    }

    private void validateSalesRequest(
            String storeExternalId,
            Instant periodStart,
            Instant periodEnd
    ) {
        if (!StringUtils.hasText(storeExternalId)) {
            throw new IllegalArgumentException("storeExternalId must not be blank");
        }
        if (periodStart == null || periodEnd == null || !periodEnd.isAfter(periodStart)) {
            throw new IllegalArgumentException(
                    "sales period must contain an end after its start"
            );
        }
    }

    private void validateSaleSummary(SaleSummaryDto sale) {
        if (!StringUtils.hasText(sale.id())
                || sale.date() == null
                || !StringUtils.hasText(sale.type())
                || sale.summ() == null) {
            throw new LiveSkladException(
                    "LiveSklad sale summary is incomplete"
            );
        }
        requireNonNegative(sale.summ().price(), "sale summary price", true);
        requireNonNegative(sale.summ().soldPrice(), "sale summary soldPrice", true);
        requireNonNegative(
                sale.summ().purchasePrice(),
                "sale summary purchasePrice",
                false
        );
    }

    private void validateSaleDetail(SaleDetailDto detail) {
        if (!StringUtils.hasText(detail.id())
                || detail.date() == null
                || !StringUtils.hasText(detail.type())
                || detail.shop() == null
                || !StringUtils.hasText(detail.shop().id())
                || detail.cash() == null
                || detail.positions() == null) {
            throw new LiveSkladException(
                    "LiveSklad sale detail is incomplete"
            );
        }
        if (detail.customer() != null
                && (!StringUtils.hasText(detail.customer().id())
                || !StringUtils.hasText(detail.customer().name()))) {
            throw new LiveSkladException(
                    "LiveSklad sale detail customer is incomplete"
            );
        }
        requireNonNegative(detail.cash().money(), "sale cash money", false);
        requireNonNegative(detail.cash().bank(), "sale cash bank", false);
        requireNonNegative(detail.cash().invoice(), "sale cash invoice", false);
    }

    private void validateSalePosition(SalePositionDto position) {
        if (!StringUtils.hasText(position.positionId())
                || !StringUtils.hasText(position.nomenclatureId())
                || !StringUtils.hasText(position.name())
                || position.isWork() == null) {
            throw new LiveSkladException(
                    "LiveSklad sale position identity is incomplete"
            );
        }
        requireNonNegative(position.count(), "sale position count", true);
        if (position.count().signum() == 0) {
            throw new LiveSkladException(
                    "LiveSklad sale position count must be positive"
            );
        }
        requireNonNegative(position.price(), "sale position price", true);
        requireNonNegative(position.soldPrice(), "sale position soldPrice", true);
        requireNonNegative(
                position.purchasePriceSumm(),
                "sale position purchasePriceSumm",
                false
        );
    }

    private BigDecimal amountOrZero(BigDecimal value) {
        requireNonNegative(value, "sale payment amount", false);
        return value == null ? BigDecimal.ZERO : value;
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

    private RestClient buildRestClient(RestClient.Builder builder, LiveSkladProperties clientProperties) {
        if (!StringUtils.hasText(clientProperties.baseUrl())) {
            return builder.build();
        }
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(clientProperties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(clientProperties.readTimeout());
        return builder
                .baseUrl(stripTrailingSlash(clientProperties.baseUrl()))
                .requestFactory(requestFactory)
                .build();
    }

    private String stripTrailingSlash(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AuthResponse(String token, long ttl) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StoresEnvelope(List<JsonNode> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StoreDto(String id, String name, String address, String color) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmployeesEnvelope(List<JsonNode> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmployeeDto(String id, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SalesEnvelope(List<JsonNode> data, Integer total) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SaleSummaryDto(
            String id,
            String number,
            Instant date,
            String type,
            SaleAmountsDto summ
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SaleAmountsDto(
            BigDecimal price,
            BigDecimal soldPrice,
            BigDecimal purchasePrice
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SaleDetailEnvelope(JsonNode data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SaleDetailDto(
            String id,
            String number,
            Instant date,
            Instant dateChange,
            String type,
            CustomerDto customer,
            ShopDto shop,
            DetailCashDto cash,
            List<JsonNode> positions
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CustomerDto(String id, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ShopDto(String id) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DetailCashDto(
            BigDecimal money,
            BigDecimal bank,
            BigDecimal invoice
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SalePositionDto(
            String positionId,
            String nomenclatureId,
            JsonNode code,
            String article,
            String name,
            Boolean isWork,
            BigDecimal count,
            BigDecimal price,
            BigDecimal soldPrice,
            BigDecimal purchasePriceSumm
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CashItemDto(
            String id,
            String name,
            String type,
            Boolean isIncome,
            Boolean isBalance
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CashRegistersEnvelope(List<JsonNode> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CashRegisterDto(String id, String name, String shopId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CashTransactionsEnvelope(
            List<JsonNode> data,
            Integer total
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CashTransactionDto(
            String id,
            Instant date,
            Instant dateChange,
            String type,
            String shopId,
            Boolean isBalance,
            Boolean isBankTransfer,
            BigDecimal money,
            RelationDto customer,
            RelationDto worker,
            RelationDto cashRegister,
            CashTransactionCashItemDto cashItem,
            RelationDto document
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CashTransactionCashItemDto(
            String id,
            String type,
            Boolean isIncome
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RelationDto(String id) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ReturnDetailDto(
            String id,
            String number,
            Instant date,
            Instant dateChange,
            String type,
            RelationDto customer,
            ShopDto shop,
            RelationDto parentDocument,
            DetailCashDto cash,
            List<JsonNode> positions
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ReturnPositionDto(
            String positionId,
            String salePositionId,
            String nomenclatureId,
            JsonNode code,
            String article,
            String name,
            Boolean isWork,
            BigDecimal count,
            BigDecimal price,
            BigDecimal soldPrice,
            BigDecimal purchasePriceSumm
    ) {
    }

    @FunctionalInterface
    private interface TokenRequest<T> {

        T execute(String token);
    }

    private record CachedToken(String value, Instant refreshAt) {

        private boolean isUsable() {
            return Instant.now().isBefore(refreshAt);
        }
    }
}
