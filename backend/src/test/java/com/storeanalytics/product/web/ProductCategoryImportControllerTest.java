package com.storeanalytics.product.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.common.web.ApiExceptionHandler;
import com.storeanalytics.product.service.ProductCategoryImportResult;
import com.storeanalytics.product.service.ProductCategoryImportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import java.util.UUID;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProductCategoryImportControllerTest {

    private ProductCategoryImportService importService;
    private MockMvc mockMvc;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        importService = mock(ProductCategoryImportService.class);
        AppUserPrincipal principal = mock(AppUserPrincipal.class);
        when(principal.getUserId()).thenReturn(UUID.randomUUID());
        authentication = new TestingAuthenticationToken(principal, null);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ProductCategoryImportController(importService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void importsValidatedRequest() throws Exception {
        when(importService.importAssignments(any(), any(UUID.class))).thenReturn(
                new ProductCategoryImportResult(1, 1, 1, 0)
        );

        mockMvc.perform(post(
                        "/api/integration-connections/livesklad-default/product-category-imports"
                )
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "validFrom": "2025-12-31T22:00:00Z",
                                  "ruleVersion": "customer-approved-2026-07-20-v1",
                                  "changeReason": "Initial customer-approved classification",
                                  "assignments": [
                                    {
                                      "externalProductId": "4310",
                                      "productName": "Cable",
                                      "categoryCode": "CHARGER_CABLE",
                                      "conditionType": "NOT_APPLICABLE"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(1))
                .andExpect(jsonPath("$.productsCreated").value(1))
                .andExpect(jsonPath("$.assignmentsCreated").value(1))
                .andExpect(jsonPath("$.assignmentsUnchanged").value(0));
    }

    @Test
    void rejectsEmptyAssignmentList() throws Exception {
        mockMvc.perform(post(
                        "/api/integration-connections/livesklad-default/product-category-imports"
                )
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "validFrom": "2025-12-31T22:00:00Z",
                                  "ruleVersion": "v1",
                                  "assignments": []
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
