package com.storeanalytics.product.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@Testcontainers
class CatalogProductReviewOpenApiIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void managerReviewEndpointsAppearInGeneratedContract() throws Exception {
        String json = mockMvc.perform(get("/v3/api-docs").with(user("contract-admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json).contains("/api/admin/catalog-product-reviews",
                "/api/admin/catalog-product-reviews/{productId}/decision",
                "CatalogProductReviewDecisionResult");
        var paths = new ObjectMapper().readTree(json).path("paths");
        var decisionParameters = paths.path("/api/admin/catalog-product-reviews/{productId}/decision")
                .path("post").path("parameters");
        assertThat(decisionParameters.size()).isEqualTo(1);
        assertThat(decisionParameters.get(0).path("name").asText()).isEqualTo("productId");
        assertThat(decisionParameters.get(0).path("in").asText()).isEqualTo("path");
        assertThat(decisionParameters.get(0).path("required").asBoolean()).isTrue();
        assertThat(decisionParameters.get(0).path("schema").path("format").asText()).isEqualTo("uuid");
        var listParameters = paths.path("/api/admin/catalog-product-reviews").path("get").path("parameters");
        assertThat(listParameters.size()).isEqualTo(1);
        assertThat(listParameters.get(0).path("name").asText()).isEqualTo("limit");
        assertThat(listParameters.get(0).path("in").asText()).isEqualTo("query");
        String output = System.getProperty("catalog.open-api.output", "");
        if (!output.isBlank()) {
            Files.writeString(Path.of(output), json);
        }
    }
}
