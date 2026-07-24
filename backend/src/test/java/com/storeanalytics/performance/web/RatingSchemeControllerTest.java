package com.storeanalytics.performance.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.common.web.ApiExceptionHandler;
import com.storeanalytics.performance.model.RatingSchemeDefinition;
import com.storeanalytics.performance.service.RatingSchemeService;
import com.storeanalytics.performance.exception.RatingSchemeConflictException;
import com.storeanalytics.performance.service.RatingSchemeView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RatingSchemeControllerTest {

    private RatingSchemeService schemeService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        schemeService = mock(RatingSchemeService.class);
        ObjectMapper objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new RatingSchemeController(schemeService))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(
                        new ApiExceptionHandler()
                )
                .build();
    }

    @Test
    void listsAndCreatesImmutableSchemeVersion() throws Exception {
        UUID actorId = UUID.randomUUID();
        RatingSchemeView view = view(actorId);
        when(schemeService.findAll()).thenReturn(List.of(view));
        when(schemeService.create(
                eq("employee-rating-v2"),
                eq(LocalDate.of(2026, 8, 1)),
                any(RatingSchemeDefinition.class),
                eq(actorId)
        )).thenReturn(view);

        mockMvc.perform(get("/api/admin/rating-schemes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("employee-rating-v2"));

        mockMvc.perform(post("/api/admin/rating-schemes")
                        .principal(authentication(actorId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.effectiveFrom").value("2026-08-01"));

        verify(schemeService).create(
                eq("employee-rating-v2"),
                eq(LocalDate.of(2026, 8, 1)),
                any(RatingSchemeDefinition.class),
                eq(actorId)
        );
    }

    @Test
    void validatesSchemeRequestAndReportsDuplicateVersion() throws Exception {
        UUID actorId = UUID.randomUUID();

        mockMvc.perform(post("/api/admin/rating-schemes")
                        .principal(authentication(actorId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        when(schemeService.create(
                eq("employee-rating-v2"),
                eq(LocalDate.of(2026, 8, 1)),
                any(RatingSchemeDefinition.class),
                eq(actorId)
        )).thenThrow(new RatingSchemeConflictException("Rating scheme code already exists"));

        mockMvc.perform(post("/api/admin/rating-schemes")
                        .principal(authentication(actorId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RATING_SCHEME_CONFLICT"));
    }

    private TestingAuthenticationToken authentication(UUID actorId) {
        AppUserPrincipal principal = mock(AppUserPrincipal.class);
        when(principal.getUserId()).thenReturn(actorId);
        return new TestingAuthenticationToken(principal, null);
    }

    private String validRequest() {
        return """
                {
                  "code": "employee-rating-v2",
                  "effectiveFrom": "2026-08-01",
                  "contributionWeight": 30.00,
                  "efficiencyWeight": 30.00,
                  "structureWeight": 20.00,
                  "attachWeight": 20.00,
                  "accessoryStructureWeight": 50.00,
                  "serviceStructureWeight": 50.00,
                  "minimumAttachDenominator": 3.000,
                  "scoreCap": 150.00,
                  "minimumCoveragePercent": 75.00
                }
                """;
    }

    private RatingSchemeView view(UUID actorId) {
        BigDecimal twenty = new BigDecimal("20.00");
        BigDecimal thirty = new BigDecimal("30.00");
        return new RatingSchemeView(
                UUID.randomUUID(),
                "employee-rating-v2",
                LocalDate.of(2026, 8, 1),
                thirty,
                thirty,
                twenty,
                twenty,
                new BigDecimal("50.00"),
                new BigDecimal("50.00"),
                new BigDecimal("3.000"),
                new BigDecimal("150.00"),
                new BigDecimal("75.00"),
                actorId,
                Instant.parse("2026-07-21T10:00:00Z")
        );
    }
}
