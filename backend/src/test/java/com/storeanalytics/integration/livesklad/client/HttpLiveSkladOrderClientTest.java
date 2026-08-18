package com.storeanalytics.integration.livesklad.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.common.config.LiveSkladProperties;
import com.storeanalytics.integration.livesklad.dto.LiveSkladOrderDetailPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladOrderSummaryPayload;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class HttpLiveSkladOrderClientTest {

    private static final String TOKEN = "fixture-access-token";

    private final ObjectMapper objectMapper = new ObjectMapper()
            .rebuild()
            .findAndAddModules()
            .build();
    private final AtomicInteger authRequests = new AtomicInteger();
    private final AtomicInteger listRequests = new AtomicInteger();
    private final AtomicInteger detailRequests = new AtomicInteger();
    private final List<String> listQueries = new CopyOnWriteArrayList<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth", this::handleAuthentication);
        server.createContext("/company/orders", this::handleOrders);
        server.createContext(
                "/orders/order-http-fixture",
                this::handleOrderDetail
        );
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void fetchesChangedOrderPagesAndMapsPositionEmployeeAndAmounts() {
        LiveSkladOrderClient client = new HttpLiveSkladOrderClient(
                RestClient.builder(),
                properties(),
                objectMapper
        );
        Instant start = Instant.parse("2026-08-01T00:00:00Z");
        Instant end = Instant.parse("2026-08-10T00:00:00Z");

        List<LiveSkladOrderSummaryPayload> orders =
                client.fetchOrders(start, end);
        LiveSkladOrderDetailPayload detail =
                client.fetchOrderDetail("order-http-fixture");

        assertThat(orders).hasSize(51);
        assertThat(orders.getFirst().externalId()).isEqualTo("order-1");
        assertThat(orders.getLast().externalId()).isEqualTo("order-51");
        assertThat(orders.getFirst().statusName()).isEqualTo("Выдан");
        assertThat(orders.getFirst().storeExternalId())
                .isEqualTo("store-http-fixture");
        assertThat(listRequests).hasValue(2);
        assertThat(listQueries.getFirst())
                .contains("lastAction=[")
                .contains("page=1")
                .contains("pageSize=50");
        assertThat(listQueries.getLast()).contains("page=2");
        assertThat(detail.externalId()).isEqualTo("order-http-fixture");
        assertThat(detail.statusName()).isEqualTo("Выдан");
        assertThat(detail.sourceUpdatedAt())
                .isEqualTo(Instant.parse("2026-08-05T15:00:00Z"));
        assertThat(detail.positions()).singleElement().satisfies(position -> {
            assertThat(position.externalId()).isEqualTo("position-1");
            assertThat(position.productExternalId()).isEqualTo("product-1");
            assertThat(position.code()).isEqualTo("4238");
            assertThat(position.employeeExternalId())
                    .isEqualTo("employee-kirill");
            assertThat(position.employeeName()).isEqualTo("Кирилл ДОЛГОВ");
            assertThat(position.unitSoldPrice())
                    .isEqualByComparingTo("18500.00");
            assertThat(position.costAmount())
                    .isEqualByComparingTo("15000.00");
        });
        assertThat(detailRequests).hasValue(1);
        assertThat(authRequests).hasValue(1);
    }

    private LiveSkladProperties properties() {
        return new LiveSkladProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-login",
                "test-password",
                Duration.ofSeconds(2),
                Duration.ofSeconds(2)
        );
    }

    private void handleAuthentication(HttpExchange exchange) throws IOException {
        authRequests.incrementAndGet();
        ObjectNode response = objectMapper.createObjectNode();
        response.put("token", TOKEN);
        response.put("ttl", 3600);
        response.put("remainRequest", 99);
        response.put("expireDate", "2026-08-18T12:00:00Z");
        sendJson(exchange, response);
    }

    private void handleOrders(HttpExchange exchange) throws IOException {
        requireToken(exchange);
        listRequests.incrementAndGet();
        String query = URLDecoder.decode(
                exchange.getRequestURI().getRawQuery(),
                StandardCharsets.UTF_8
        );
        listQueries.add(query);
        int page = query.contains("page=2") ? 2 : 1;
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode data = response.putArray("data");
        int start = page == 1 ? 1 : 51;
        int end = page == 1 ? 50 : 51;
        for (int index = start; index <= end; index++) {
            ObjectNode order = data.addObject();
            order.put("id", "order-" + index);
            order.put("number", "A" + index);
            order.put("dateCreate", "2026-08-01T08:00:00Z");
            order.put("isVisible", true);
            relation(order.putObject("status"), "status-issued", "Выдан");
            relation(
                    order.putObject("shop"),
                    "store-http-fixture",
                    "МАГАЗИН"
            );
        }
        response.put("total", 51);
        response.put("remainRequest", 98 - page);
        response.put("expireDate", "2026-08-18T12:00:00Z");
        sendJson(exchange, response);
    }

    private void handleOrderDetail(HttpExchange exchange) throws IOException {
        requireToken(exchange);
        detailRequests.incrementAndGet();
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode detail = response.putObject("data");
        detail.put("id", "order-http-fixture");
        detail.put("number", "A000605");
        detail.put("dateCreate", "2026-07-25T13:15:57.535Z");
        detail.put("lastAction", "2026-08-05T15:00:00Z");
        detail.put("dateClose", "2026-08-05T14:00:00Z");
        detail.put("isVisible", true);
        relation(detail.putObject("status"), "status-issued", "Выдан");
        relation(
                detail.putObject("shop"),
                "store-http-fixture",
                "МАГАЗИН"
        );
        ObjectNode position = detail.putArray("positions").addObject();
        position.put("positionId", "position-1");
        position.put("nomenclatureId", "product-1");
        position.put("code", 4238);
        position.put("name", "Замена Контроллера питания 1");
        position.put("isWork", true);
        position.put("count", new BigDecimal("1.000"));
        position.put("price", new BigDecimal("18500.00"));
        position.put("soldPrice", new BigDecimal("18500.00"));
        position.put("purchasePriceSumm", new BigDecimal("15000.00"));
        position.put("date", "2026-08-05T12:08:00Z");
        relation(
                position.putObject("customer"),
                "employee-kirill",
                "Кирилл ДОЛГОВ"
        );
        response.put("remainRequest", 95);
        response.put("expireDate", "2026-08-18T12:00:00Z");
        sendJson(exchange, response);
    }

    private void relation(ObjectNode node, String id, String name) {
        node.put("id", id);
        node.put("name", name);
    }

    private void requireToken(HttpExchange exchange) throws IOException {
        if (!TOKEN.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        }
    }

    private void sendJson(HttpExchange exchange, ObjectNode response)
            throws IOException {
        byte[] body = objectMapper.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
