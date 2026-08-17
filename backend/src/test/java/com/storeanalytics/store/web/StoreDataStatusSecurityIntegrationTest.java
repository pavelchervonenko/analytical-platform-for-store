package com.storeanalytics.store.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.auth.model.UserStoreAccess;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.auth.repository.UserStoreAccessRepository;
import com.storeanalytics.common.web.ApiContractVersion;
import com.storeanalytics.performance.model.StorePerformancePlan;
import com.storeanalytics.performance.model.StorePlanTargets;
import com.storeanalytics.performance.repository.StorePerformancePlanRepository;
import com.storeanalytics.performance.repository.EmployeeRatingSnapshotRepository;
import com.storeanalytics.quality.model.DataQualityIssue;
import com.storeanalytics.quality.model.DataQualitySeverity;
import com.storeanalytics.quality.repository.DataQualityIssueRepository;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.model.StoreSchedule;
import com.storeanalytics.store.repository.StoreRepository;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class StoreDataStatusSecurityIntegrationTest {

    private static final String PASSWORD = "correct horse battery staple";

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private UserStoreAccessRepository accessRepository;

    @Autowired
    private StoreRepository storeRepository;
    @Autowired
    private DataQualityIssueRepository qualityIssueRepository;
    @Autowired
    private StorePerformancePlanRepository performancePlanRepository;
    @Autowired
    private EmployeeRatingSnapshotRepository ratingSnapshotRepository;


    @Autowired
    private PasswordEncoder passwordEncoder;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void cleanDatabase() {
        ratingSnapshotRepository.deleteAll();
        performancePlanRepository.deleteAll();
        qualityIssueRepository.deleteAll();
        accessRepository.deleteAll();
        userRepository.deleteAll();
        storeRepository.deleteAll();
    }

    @Test
    void managerCannotReadAnotherStoreWhileAdministratorCan() throws Exception {
        Store assignedStore = createStore("assigned-status-store");
        Store deniedStore = createStore("denied-status-store");
        AppUser manager = createUser("manager-status@example.com", UserRole.MANAGER);
        AppUser administrator = createUser("admin-status@example.com", UserRole.ADMIN);
        performancePlanRepository.saveAndFlush(new StorePerformancePlan(
                assignedStore,
                LocalDate.of(2026, 7, 1),
                new StorePlanTargets(
                        new BigDecimal("24000000.00"),
                        new BigDecimal("3.90"),
                        new BigDecimal("3.00"),
                        new BigDecimal("7.00")
                ),
                administrator
        ));
        accessRepository.save(new UserStoreAccess(manager, assignedStore, administrator));
        qualityIssueRepository.saveAndFlush(DataQualityIssue.open(
                assignedStore,
                "SALE",
                "external-id-must-not-be-exposed",
                "SALE_PAYMENT_MISMATCH",
                DataQualitySeverity.WARNING,
                "raw persisted message must not be exposed",
                Instant.parse("2026-07-22T09:00:00Z")
        ));

        MockHttpSession managerSession = login("manager-status@example.com");
        Cookie managerCsrf = csrfCookie(managerSession);
        mockMvc.perform(get(
                        "/api/stores/{storeId}/performance-plans/{month}/progress",
                        assignedStore.getId(),
                        "2026-07"
                ).queryParam("asOf", "2026-07-20").session(managerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(assignedStore.getId().toString()))
                .andExpect(jsonPath("$.directions[0].code").value("REVENUE"));
        mockMvc.perform(get(
                        "/api/stores/{storeId}/performance-plans/{month}/progress",
                        deniedStore.getId(),
                        "2026-07"
                ).queryParam("asOf", "2026-07-20").session(managerSession))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(
                        "/api/stores/{storeId}/employee-ratings/finalize",
                        assignedStore.getId()
                ).cookie(managerCsrf)
                .header("X-XSRF-TOKEN", managerCsrf.getValue())
                .queryParam("periodStart", "2026-06-01")
                .queryParam("periodEnd", "2026-06-30")
                .session(managerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history.status").value("FINALIZED"));
        mockMvc.perform(post(
                        "/api/stores/{storeId}/employee-ratings/finalize",
                        deniedStore.getId()
                ).cookie(managerCsrf)
                .header("X-XSRF-TOKEN", managerCsrf.getValue())
                .queryParam("periodStart", "2026-06-01")
                .queryParam("periodEnd", "2026-06-30")
                .session(managerSession))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/stores/{storeId}/data-status", assignedStore.getId())
                        .session(managerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_SYNCED"));
        mockMvc.perform(get("/api/stores/{storeId}/data-status", deniedStore.getId())
                        .session(managerSession))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(
                        "/api/stores/{storeId}/interpretations/weekly/latest",
                        assignedStore.getId()
                ).session(managerSession))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("WEEKLY_INTERPRETATION_NOT_FOUND"));
        mockMvc.perform(get(
                        "/api/stores/{storeId}/interpretations/weekly/latest",
                        deniedStore.getId()
                ).session(managerSession))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/stores/{storeId}/data-quality", assignedStore.getId())
                        .session(managerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.status").value("ERROR"))
                .andExpect(jsonPath("$.issues[0].code").value("DATA_NOT_SYNCED"))
                .andExpect(jsonPath("$.issues[1].code").value("SALE_PAYMENT_MISMATCH"))
                .andExpect(jsonPath("$.issues[1].message").value(
                        "Sale payments do not match the document total"
                ))
                .andExpect(jsonPath("$.issues[1].entityId").doesNotExist())
                .andExpect(jsonPath("$.issues[1].metadata").doesNotExist());
        mockMvc.perform(get("/api/stores/{storeId}/data-quality", deniedStore.getId())
                        .session(managerSession))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(
                        "/api/stores/{storeId}/period-quality/{month}",
                        assignedStore.getId(),
                        "2026-07"
                ).queryParam("asOf", "2026-07-20").session(managerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(assignedStore.getId().toString()))
                .andExpect(jsonPath("$.areas").isArray())
                .andExpect(jsonPath("$.sourceData.freshnessStatus").value("NOT_SYNCED"))
                .andExpect(jsonPath("$.issues[0].severity").value("ERROR"));
        mockMvc.perform(get(
                        "/api/stores/{storeId}/period-quality/{month}",
                        deniedStore.getId(),
                        "2026-07"
                ).queryParam("asOf", "2026-07-20").session(managerSession))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/data-quality/summary").session(managerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeCount").value(1))
                .andExpect(jsonPath("$.openIssueCount").value(2))
                .andExpect(jsonPath("$.stores[0].storeId")
                        .value(assignedStore.getId().toString()));

        MockHttpSession adminSession = login("admin-status@example.com");
        mockMvc.perform(get(
                        "/api/stores/{storeId}/performance-plans/{month}/progress",
                        assignedStore.getId(),
                        "2026-07"
                ).queryParam("asOf", "2026-07-20").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.formulaVersion").value("store-plan-progress-v2"));
        mockMvc.perform(get("/api/stores/{storeId}/data-status", deniedStore.getId())
                        .session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(deniedStore.getId().toString()))
                .andExpect(jsonPath("$.status").value("NOT_SYNCED"));
        mockMvc.perform(get("/api/stores/{storeId}/data-quality", deniedStore.getId())
                        .session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.storeId")
                        .value(deniedStore.getId().toString()));
        mockMvc.perform(get("/api/data-quality/summary").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeCount").value(2))
                .andExpect(jsonPath("$.errorStoreCount").value(2));
    }

    @Test
    void administratorOpenApiContainsStableFrontendSchemas() throws Exception {
        createUser("admin-openapi@example.com", UserRole.ADMIN);
        MockHttpSession adminSession = login("admin-openapi@example.com");

        MvcResult openApiResult = mockMvc.perform(
                        get("/v3/api-docs").session(adminSession)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.version").value(ApiContractVersion.CURRENT))
                .andExpect(jsonPath("$.paths['/api/data-quality/summary']").exists())
                .andExpect(jsonPath(
                        "$.paths['/api/sync/jobs/backfill-readiness']"
                ).exists())
                .andExpect(jsonPath(
                        "$.components.schemas.SyncClassificationReadinessView"
                ).exists())
                .andExpect(jsonPath(
                        "$.paths['/api/stores/{storeId}/data-quality']"
                ).exists())
                .andExpect(jsonPath(
                        "$.paths['/api/stores/{storeId}/interpretations/weekly']"
                ).exists())
                .andExpect(jsonPath(
                        "$.paths['/api/stores/{storeId}/interpretations/weekly/latest']"
                ).exists())
                .andExpect(jsonPath(
                        "$.paths['/api/stores/{storeId}/interpretations/weekly/"
                                + "{interpretationId}']"
                ).exists())
                .andExpect(jsonPath(
                        "$.components.schemas.WeeklyInterpretationSummaryView.properties"
                                + ".contentHash"
                ).exists())
                .andExpect(jsonPath(
                        "$.components.schemas.WeeklyInterpretationDetailView.properties"
                                + ".employees"
                ).exists())
                .andExpect(jsonPath(
                        "$.paths['/api/stores/{storeId}/period-quality/{month}']"
                ).exists())
                .andExpect(jsonPath(
                        "$.paths['/api/stores/{storeId}/performance-plans/{month}/progress']"
                ).exists())
                .andExpect(jsonPath(
                        "$.paths['/api/stores/{storeId}/employee-ratings/finalize']"
                ).exists())
                .andExpect(jsonPath(
                        "$.components.schemas.StorePeriodQualityView.properties.areas"
                ).exists())
                .andExpect(jsonPath(
                        "$.components.schemas.StorePeriodQualityView.properties.issues"
                ).exists())
                .andExpect(jsonPath(
                        "$.components.schemas.PeriodPayrollQualityView.properties.freshness"
                ).exists())
                .andExpect(jsonPath(
                        "$.components.schemas.StorePlanProgressView.properties.directions"
                ).exists())
                .andExpect(jsonPath(
                        "$.components.schemas.StorePlanDirectionView.properties"
                                + ".criterionCompletionPercent"
                ).exists())
                .andExpect(jsonPath(
                        "$.components.schemas.StorePlanProgressView.properties.dailyTargets"
                ).exists())
                .andExpect(jsonPath(
                        "$.components.schemas.StorePlanDailyTargetView.properties.accessory"
                ).exists())
                .andExpect(jsonPath(
                        "$.components.schemas.StorePlanDailyDirectionView.properties"
                                + ".targetSharePercent"
                ).exists())
                .andExpect(jsonPath(
                        "$.components.schemas.StoreDataQualityView.properties.summary"
                ).exists())
                .andExpect(jsonPath(
                        "$.components.schemas.PayrollPreviewView.properties.planResult"
                ).exists())
                .andExpect(jsonPath(
                        "$.components.schemas.PayrollPreviewView.properties.actualScenario"
                ).exists())
                .andExpect(jsonPath(
                        "$.components.schemas.PayrollPreviewView.properties.achievedScenario"
                ).doesNotExist())
                .andExpect(jsonPath(
                        "$.components.schemas.PayrollPreviewView.properties.selectedScenario"
                ).doesNotExist())
                .andExpect(jsonPath(
                        "$.components.schemas.PayrollRunSummaryView.properties.freshness"
                ).exists())
                .andExpect(jsonPath(
                        "$.components.schemas.PayrollFreshnessView.properties.status"
                ).exists())
                .andExpect(jsonPath(
                        "$.components.schemas.PayrollFreshnessView.properties.reasons"
                ).exists())
                .andExpect(jsonPath(
                        "$.components.schemas.EmployeeRatingResult.properties.history"
                ).exists())
                .andExpect(jsonPath(
                        "$.components.schemas.EmployeeRatingHistoryView.properties.status"
                ).exists())
                .andExpect(jsonPath(
                        "$.components.schemas.WorkScheduleShiftRequest.properties.workedHours"
                ).exists())
                .andExpect(jsonPath(
                        "$.paths['/api/stores/{storeId}/performance-plans/{month}']"
                                + ".get.responses['200'].headers.ETag"
                ).exists())
                .andExpect(jsonPath(
                        "$.paths['/api/stores/{storeId}/work-schedule/{workDate}']"
                                + ".get.responses['200'].headers.ETag"
                ).exists())
                .andExpect(jsonPath(
                        "$.paths['/api/stores/{storeId}/work-schedule/{workDate}']"
                                + ".put.parameters[?(@.name == 'If-Match' && @.required == true)]"
                ).exists())
                .andExpect(jsonPath(
                        "$.paths['/api/admin/reports/backfill'].post.parameters"
                                + "[?(@.name == 'Idempotency-Key' && @.required == true)]"
                ).exists())
                .andExpect(jsonPath(
                        "$.components.schemas.WorkScheduleDayView.properties.revision"
                ).exists())
                .andExpect(jsonPath(
                        "$.components.schemas.PayrollPreviewAllocationView.properties.workedHours"
                ).exists())
                .andExpect(jsonPath(
                        "$.components.schemas.PayrollDailyAllocationView.properties.workedHours"
                ).exists())
                .andExpect(jsonPath(
                        "$.components.schemas.CategoryKpiMetrics.properties"
                                + ".averageGrossProfitPerUnit"
                ).exists())
                .andExpect(jsonPath(
                        "$.components.schemas.SystemStatusView.properties.apiContractVersion"
                ).exists())
                .andReturn();
        writeOpenApiArtifact(openApiResult);
    }

    private void writeOpenApiArtifact(MvcResult result) throws IOException {
        String configuredOutput = System.getProperty("openapi.output", "").trim();
        if (configuredOutput.isEmpty()) {
            return;
        }
        Path output = Path.of(configuredOutput).toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        Files.writeString(
                output,
                result.getResponse().getContentAsString(),
                StandardCharsets.UTF_8
        );
    }

    private MockHttpSession login(String email) throws Exception {
        Cookie csrfCookie = csrfCookie();
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private Cookie csrfCookie(MockHttpSession session) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/csrf").session(session))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private Cookie csrfCookie() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private AppUser createUser(String email, UserRole role) {
        AppUser user = new AppUser(email, passwordEncoder.encode(PASSWORD), email, role);
        user.changePassword(passwordEncoder.encode(PASSWORD));
        return userRepository.saveAndFlush(user);
    }

    private Store createStore(String externalId) {
        return storeRepository.saveAndFlush(Store.manual(
                externalId,
                externalId,
                null,
                new StoreSchedule(
                        "Europe/Kaliningrad",
                        LocalTime.MIDNIGHT,
                        LocalTime.of(10, 0),
                        LocalTime.of(21, 0)
                )
        ));
    }
}
