package com.storeanalytics.integration.livesklad.client;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.storeanalytics.common.config.LiveSkladProperties;
import com.storeanalytics.integration.livesklad.dto.LiveSkladCashItemPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladCashRegisterPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladCashTransactionPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladEmployeePayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladReturnDetailPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladSaleDetailPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladSaleSummaryPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladStorePayload;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class HttpLiveSkladClientTest {

    private static final String ACCESS_TOKEN = "fixture-access-token";

    private final ObjectMapper objectMapper = new ObjectMapper().rebuild().findAndAddModules().build();
    private final AtomicInteger authRequests = new AtomicInteger();
    private final AtomicInteger storeRequests = new AtomicInteger();
    private final AtomicInteger employeeRequests = new AtomicInteger();
    private final AtomicInteger saleRequests = new AtomicInteger();
    private final AtomicInteger saleDetailRequests = new AtomicInteger();
    private final List<String> employeeQueries = new CopyOnWriteArrayList<>();
    private final List<String> saleQueries = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> authorizationHeader = new AtomicReference<>();
    private final AtomicReference<String> authenticationBody = new AtomicReference<>();
    private final AtomicInteger cashItemRequests = new AtomicInteger();
    private final AtomicInteger cashRegisterRequests = new AtomicInteger();
    private final AtomicInteger cashTransactionRequests = new AtomicInteger();
    private final AtomicInteger returnDetailRequests = new AtomicInteger();
    private final List<String> cashQueries = new CopyOnWriteArrayList<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth", this::handleAuthentication);
        server.createContext(
                "/shops/store-http-fixture/customers",
                this::handleEmployees
        );
        server.createContext("/shops", this::handleStores);
        server.createContext(
                "/shops/store-http-fixture/sales",
                this::handleSales
        );
        server.createContext(
                "/documents/sale-http-fixture",
                this::handleSaleDetail
        );
        server.createContext("/cash-items", this::handleCashItems);
        server.createContext(
                "/shops/store-http-fixture/cash-registers",
                this::handleCashRegisters
        );
        server.createContext(
                "/cash-registers/register-http-fixture/cash",
                this::handleCashTransactions
        );
        server.createContext(
                "/documents/return-http-fixture",
                this::handleReturnDetail
        );
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void authenticatesWithFormDataCachesTokenAndSendsTokenWithoutBearerPrefix() {
        LiveSkladProperties properties = new LiveSkladProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-login",
                "test-password",
                Duration.ofSeconds(2),
                Duration.ofSeconds(2)
        );
        LiveSkladClient client = new HttpLiveSkladClient(
                RestClient.builder(),
                properties,
                objectMapper
        );

        List<LiveSkladStorePayload> first = client.fetchStores();
        List<LiveSkladStorePayload> second = client.fetchStores();

        assertThat(first).hasSize(1);
        assertThat(first.getFirst().externalId()).isEqualTo("store-http-fixture");
        assertThat(second).hasSize(1);
        assertThat(authRequests).hasValue(1);
        assertThat(storeRequests).hasValue(2);
        assertThat(authenticationBody.get())
                .isEqualTo("login=test-login&password=test-password");
        assertThat(authorizationHeader.get()).isEqualTo(ACCESS_TOKEN);
    }
    @Test
    void fetchesAllEmployeePagesUntilShortPage() {
        LiveSkladProperties properties = new LiveSkladProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-login",
                "test-password",
                Duration.ofSeconds(2),
                Duration.ofSeconds(2)
        );
        LiveSkladClient client = new HttpLiveSkladClient(
                RestClient.builder(),
                properties,
                objectMapper
        );

        List<LiveSkladEmployeePayload> employees =
                client.fetchEmployees("store-http-fixture");

        assertThat(employees).hasSize(51);
        assertThat(employees.getFirst().externalId()).isEqualTo("employee-1");
        assertThat(employees.getLast().externalId()).isEqualTo("employee-51");
        assertThat(employeeRequests).hasValue(2);
        assertThat(employeeQueries).containsExactly(
                "page=1&pageSize=50",
                "page=2&pageSize=50"
        );
        assertThat(authRequests).hasValue(1);
        assertThat(authorizationHeader.get()).isEqualTo(ACCESS_TOKEN);
    }


    @Test
    void fetchesSalesPagesWithFixedPeriodAndMapsSaleDetail() {
        LiveSkladProperties properties = new LiveSkladProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-login",
                "test-password",
                Duration.ofSeconds(2),
                Duration.ofSeconds(2)
        );
        LiveSkladClient client = new HttpLiveSkladClient(
                RestClient.builder(),
                properties,
                objectMapper
        );
        Instant periodStart = Instant.parse("2026-07-01T00:00:00Z");
        Instant periodEnd = Instant.parse("2026-07-01T23:59:59.999Z");

        List<LiveSkladSaleSummaryPayload> sales = client.fetchSales(
                "store-http-fixture",
                periodStart,
                periodEnd
        );
        LiveSkladSaleDetailPayload detail =
                client.fetchSaleDetail("sale-http-fixture");

        assertThat(sales).hasSize(51);
        assertThat(sales.getFirst().externalId()).isEqualTo("sale-1");
        assertThat(sales.getLast().externalId()).isEqualTo("sale-51");
        assertThat(saleRequests).hasValue(2);
        assertThat(saleQueries).hasSize(2);
        assertThat(saleQueries.getFirst())
                .contains("date=[")
                .contains("page=1")
                .contains("pageSize=50")
                .contains("sort=date ASC");
        assertThat(saleQueries.getLast()).contains("page=2");
        assertThat(detail.externalId()).isEqualTo("sale-http-fixture");
        assertThat(detail.employeeExternalId()).isEqualTo("employee-1");
        assertThat(detail.cashAmount()).isEqualByComparingTo("40.00");
        assertThat(detail.cardAmount()).isEqualByComparingTo("60.00");
        assertThat(detail.positions()).singleElement().satisfies(position -> {
            assertThat(position.externalId()).isEqualTo("position-http-fixture");
            assertThat(position.productExternalId()).isEqualTo("product-http-fixture");
            assertThat(position.code()).isEqualTo("5");
            assertThat(position.quantity()).isEqualByComparingTo("1");
        });
        assertThat(saleDetailRequests).hasValue(1);
        assertThat(authRequests).hasValue(1);
    }

    @Test
    void mapsCashRegistersTransactionsAndReturnDetails() {
        LiveSkladProperties properties = new LiveSkladProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-login",
                "test-password",
                Duration.ofSeconds(2),
                Duration.ofSeconds(2)
        );
        LiveSkladClient client = new HttpLiveSkladClient(
                RestClient.builder(),
                properties,
                objectMapper
        );
        Instant start = Instant.parse("2026-07-01T00:00:00Z");
        Instant end = Instant.parse("2026-07-02T00:00:00Z");

        List<LiveSkladCashItemPayload> cashItems = client.fetchCashItems();
        List<LiveSkladCashRegisterPayload> registers =
                client.fetchCashRegisters("store-http-fixture");
        List<LiveSkladCashTransactionPayload> transactions =
                client.fetchCashTransactions(
                        "register-http-fixture",
                        "cash-item-http-fixture",
                        start,
                        end
                );
        LiveSkladReturnDetailPayload detail =
                client.fetchReturnDetail("return-http-fixture");

        assertThat(cashItems).hasSize(4);
        assertThat(cashItems)
                .extracting(LiveSkladCashItemPayload::sourceType)
                .containsExactly("saleReturn", null, null, "futureType");
        assertThat(cashItems.getFirst().income()).isFalse();
        assertThat(cashItems.get(1).income()).isTrue();
        assertThat(registers).singleElement().satisfies(register -> {
            assertThat(register.externalId())
                    .isEqualTo("register-http-fixture");
            assertThat(register.storeExternalId())
                    .isEqualTo("store-http-fixture");
        });
        assertThat(transactions).singleElement().satisfies(transaction -> {
            assertThat(transaction.documentExternalId())
                    .isEqualTo("return-http-fixture");
            assertThat(transaction.amount()).isEqualByComparingTo("90.00");
            assertThat(transaction.deleted()).isFalse();
        });
        assertThat(detail.originalSaleExternalId())
                .isEqualTo("sale-http-fixture");
        assertThat(detail.positions()).singleElement().satisfies(position -> {
            assertThat(position.externalId())
                    .isEqualTo("return-position-http-fixture");
            assertThat(position.originalSalePositionExternalId())
                    .isEqualTo("position-http-fixture");
        });
        assertThat(cashItemRequests).hasValue(1);
        assertThat(cashRegisterRequests).hasValue(1);
        assertThat(cashTransactionRequests).hasValue(1);
        assertThat(returnDetailRequests).hasValue(1);
        assertThat(cashQueries).singleElement().satisfies(query ->
                assertThat(query)
                        .contains("cashItemId=cash-item-http-fixture")
                        .contains("pageSize=50")
                        .contains("sort=date ASC")
        );
        assertThat(authRequests).hasValue(1);
    }

    private void handleAuthentication(HttpExchange exchange) throws IOException {
        authRequests.incrementAndGet();
        authenticationBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        sendJson(exchange, 200, """
                {
                  "token": "fixture-access-token",
                  "ttl": 900,
                  "remainRequest": 99
                }
                """);
    }

    private void handleStores(HttpExchange exchange) throws IOException {
        storeRequests.incrementAndGet();
        authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
        sendJson(exchange, 200, """
                {
                  "data": [
                    {
                      "id": "store-http-fixture",
                      "name": "HTTP Fixture Store",
                      "address": "Example Address",
                      "color": "#123456"
                    }
                  ],
                  "remainRequest": 98
                }
                """);
    }

    private void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private void handleSales(HttpExchange exchange) throws IOException {
        saleRequests.incrementAndGet();
        authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
        String query = URLDecoder.decode(
                exchange.getRequestURI().getRawQuery(),
                StandardCharsets.UTF_8
        );
        saleQueries.add(query);
        int page = query.contains("page=2") ? 2 : 1;
        int firstSale = page == 1 ? 1 : 51;
        int saleCount = page == 1 ? 50 : 1;

        ArrayNode data = objectMapper.createArrayNode();
        for (int index = 0; index < saleCount; index++) {
            int saleNumber = firstSale + index;
            ObjectNode sale = data.addObject();
            sale.put("id", "sale-" + saleNumber);
            sale.put("number", "S-" + saleNumber);
            sale.put("date", "2026-07-01T10:00:00Z");
            sale.put("type", "sale");
            ObjectNode amounts = sale.putObject("summ");
            amounts.put("price", 100);
            amounts.put("soldPrice", 90);
            amounts.put("purchasePrice", 50);
            ObjectNode cash = sale.putObject("cash");
            cash.put("summ", 90);
            cash.put("isBank", false);
            cash.put("isMoney", true);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("data", data);
        response.put("total", 51);
        response.put("page", page);
        response.put("pageSize", 50);
        sendJson(exchange, 200, objectMapper.writeValueAsString(response));
    }

    private void handleSaleDetail(HttpExchange exchange) throws IOException {
        saleDetailRequests.incrementAndGet();
        authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
        sendJson(exchange, 200, """
                {
                  "data": {
                    "id": "sale-http-fixture",
                    "number": "S-HTTP",
                    "date": "2026-07-01T10:00:00Z",
                    "dateChange": "2026-07-01T10:05:00Z",
                    "type": "sale",
                    "customer": {
                      "id": "employee-1",
                      "name": "Employee 1"
                    },
                    "shop": {
                      "id": "store-http-fixture"
                    },
                    "cash": {
                      "money": 40.00,
                      "bank": 60.00,
                      "invoice": 0,
                      "elements": []
                    },
                    "positions": [
                      {
                        "positionId": "position-http-fixture",
                        "nomenclatureId": "product-http-fixture",
                        "code": 5,
                        "article": "SKU-HTTP",
                        "name": "HTTP Product",
                        "isWork": false,
                        "count": 1,
                        "price": 100.00,
                        "soldPrice": 100.00,
                        "purchasePriceSumm": 60.00
                      }
                    ]
                  }
                }
                """);
    }

    private void handleCashItems(HttpExchange exchange) throws IOException {
        cashItemRequests.incrementAndGet();
        sendJson(exchange, 200, """
                {
                  "data": [
                    {
                      "id": "cash-item-http-fixture",
                      "name": "Sale return",
                      "type": "saleReturn",
                      "isIncome": false,
                      "isBalance": true
                    },
                    {
                      "id": "cash-item-without-type",
                      "name": "Manual adjustment",
                      "isIncome": true,
                      "isBalance": false
                    },
                    {
                      "id": "cash-item-null-type",
                      "name": "Null type adjustment",
                      "type": null,
                      "isIncome": true,
                      "isBalance": false
                    },
                    {
                      "id": "cash-item-future-type",
                      "name": "Future adjustment",
                      "type": "futureType",
                      "isIncome": false,
                      "isBalance": false
                    }
                  ]
                }
                """);
    }

    private void handleCashRegisters(HttpExchange exchange)
            throws IOException {
        cashRegisterRequests.incrementAndGet();
        sendJson(exchange, 200, """
                {
                  "data": [
                    {
                      "id": "register-http-fixture",
                      "name": "HTTP Register",
                      "shopId": "store-http-fixture",
                      "cashMoney": 100,
                      "bankMoney": 200
                    }
                  ]
                }
                """);
    }

    private void handleCashTransactions(HttpExchange exchange)
            throws IOException {
        cashTransactionRequests.incrementAndGet();
        cashQueries.add(URLDecoder.decode(
                exchange.getRequestURI().getRawQuery(),
                StandardCharsets.UTF_8
        ));
        sendJson(exchange, 200, """
                {
                  "data": [
                    {
                      "id": "cash-return-http-fixture",
                      "date": "2026-07-01T12:00:00Z",
                      "dateChange": "2026-07-01T12:01:00Z",
                      "type": "saleReturn",
                      "shopId": "store-http-fixture",
                      "isBalance": true,
                      "isBankTransfer": false,
                      "money": 90.00,
                      "customer": {
                        "id": "employee-1"
                      },
                      "cashRegister": {
                        "id": "register-http-fixture"
                      },
                      "cashItem": {
                        "id": "cash-item-http-fixture",
                        "type": "saleReturn",
                        "isIncome": false
                      },
                      "document": {
                        "id": "return-http-fixture"
                      }
                    }
                  ],
                  "total": 1
                }
                """);
    }

    private void handleReturnDetail(HttpExchange exchange)
            throws IOException {
        returnDetailRequests.incrementAndGet();
        sendJson(exchange, 200, """
                {
                  "data": {
                    "id": "return-http-fixture",
                    "number": "R-HTTP",
                    "date": "2026-07-01T12:00:00Z",
                    "dateChange": "2026-07-01T12:02:00Z",
                    "type": "saleReturn",
                    "customer": {
                      "id": "return-processor"
                    },
                    "shop": {
                      "id": "store-http-fixture"
                    },
                    "parentDocument": {
                      "id": "sale-http-fixture"
                    },
                    "cash": {
                      "money": 90.00,
                      "bank": 0,
                      "invoice": 0
                    },
                    "positions": [
                      {
                        "positionId": "return-position-http-fixture",
                        "salePositionId": "position-http-fixture",
                        "nomenclatureId": "product-http-fixture",
                        "code": 5,
                        "article": "SKU-HTTP",
                        "name": "HTTP Product",
                        "isWork": false,
                        "count": 1,
                        "price": 100.00,
                        "soldPrice": 90.00,
                        "purchasePriceSumm": 60.00
                      }
                    ]
                  }
                }
                """);
    }

    private void handleEmployees(HttpExchange exchange) throws IOException {
        employeeRequests.incrementAndGet();
        authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
        String query = exchange.getRequestURI().getQuery();
        employeeQueries.add(query);
        int page = query.startsWith("page=2") ? 2 : 1;
        int firstEmployee = page == 1 ? 1 : 51;
        int employeeCount = page == 1 ? 50 : 1;

        ArrayNode data = objectMapper.createArrayNode();
        for (int index = 0; index < employeeCount; index++) {
            int employeeNumber = firstEmployee + index;
            ObjectNode employee = data.addObject();
            employee.put("id", "employee-" + employeeNumber);
            employee.put("name", "Employee " + employeeNumber);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("data", data);
        response.put("remainRequest", 97);
        sendJson(exchange, 200, objectMapper.writeValueAsString(response));
    }
}

