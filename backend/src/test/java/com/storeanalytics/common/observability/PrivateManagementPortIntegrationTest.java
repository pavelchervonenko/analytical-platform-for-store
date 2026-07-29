package com.storeanalytics.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=0",
            "app.observability.prometheus.token="
                    + "private-management-port-test-token"
        }
)
@AutoConfigureMetrics
@AutoConfigureTestRestTemplate
@Testcontainers(disabledWithoutDocker = true)
class PrivateManagementPortIntegrationTest {

    private static final String TOKEN =
            "private-management-port-test-token";

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalServerPort
    private int applicationPort;

    @LocalManagementPort
    private int managementPort;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void exposesAuthorizedScrapeOnlyOnManagementPort() {
        String managementUrl = url(managementPort);

        ResponseEntity<String> missingToken = restTemplate.getForEntity(
                managementUrl,
                String.class
        );
        assertThat(missingToken.getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(missingToken.getHeaders().getFirst(
                HttpHeaders.WWW_AUTHENTICATE
        )).isEqualTo("Bearer");

        ResponseEntity<String> authorized = restTemplate.exchange(
                managementUrl,
                HttpMethod.GET,
                bearerRequest(),
                String.class
        );
        assertThat(authorized.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(authorized.getBody())
                .contains("storeanalytics_release_info");

        ResponseEntity<String> applicationPortResponse = restTemplate.exchange(
                url(applicationPort),
                HttpMethod.GET,
                bearerRequest(),
                String.class
        );
        assertThat(applicationPortResponse.getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private HttpEntity<Void> bearerRequest() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TOKEN);
        return new HttpEntity<>(headers);
    }

    private String url(int port) {
        return "http://127.0.0.1:" + port + "/actuator/prometheus";
    }
}
