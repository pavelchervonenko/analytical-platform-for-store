package com.storeanalytics.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storeanalytics.audit.repository.AuditLogRepository;
import com.storeanalytics.audit.service.AuditAction;
import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.auth.model.UserStoreAccess;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.auth.repository.UserStoreAccessRepository;
import com.storeanalytics.common.web.ApiContractVersion;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.model.StoreSchedule;
import com.storeanalytics.store.repository.StoreRepository;
import jakarta.servlet.http.Cookie;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.mock.web.MockHttpSession;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class AuthIntegrationTest {

    private static final String PASSWORD = "correct horse battery staple";
    private static final String NEW_PASSWORD = "passw0rd-2026";
    private static final UUID BREAK_GLASS_USER_ID =
            UUID.fromString("4fe56874-f1d7-4ca5-97be-09809f2f53fb");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private UserStoreAccessRepository accessRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;


    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add(
                "app.security.break-glass.user-ids",
                () -> BREAK_GLASS_USER_ID.toString()
        );
    }

    @BeforeEach
    void cleanDatabase() {
        accessRepository.deleteAll();
        userRepository.deleteAll();
        storeRepository.deleteAll();
    }

    @Test
    void authenticatesDatabaseUserAndCreatesSession() throws Exception {
        createUser("manager@example.com", UserRole.MANAGER, false);

        MvcResult loginResult = login("MANAGER@example.com", PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("manager@example.com"))
                .andExpect(jsonPath("$.role").value("MANAGER"))
                .andExpect(jsonPath("$.passwordChangeRequired").value(false))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn();

        MockHttpSession session = session(loginResult);
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("manager@example.com"));
        assertThat(loginResult.getResponse().getContentAsString()).doesNotContain(PASSWORD);
    }

    @Test
    void migratesLegacyNonNfcPasswordHashAfterSuccessfulLogin() throws Exception {
        String decomposedPassword = "Cafe\u0301 password 2026";
        String nfcPassword = "Caf\u00e9 password 2026";
        String legacyHash = "{bcrypt}"
                + new BCryptPasswordEncoder(12).encode(decomposedPassword);
        AppUser user = new AppUser(
                "unicode@example.com",
                legacyHash,
                "Unicode User",
                UserRole.MANAGER
        );
        user.changePassword(legacyHash);
        user = userRepository.saveAndFlush(user);

        login(user.getEmail(), decomposedPassword)
                .andExpect(status().isOk());

        AppUser migratedUser = userRepository.findById(user.getId()).orElseThrow();
        assertThat(migratedUser.getPasswordHash()).isNotEqualTo(legacyHash);
        assertThat(passwordEncoder.matches(nfcPassword, migratedUser.getPasswordHash()))
                .isTrue();
        login(user.getEmail(), nfcPassword)
                .andExpect(status().isOk());
    }

    @Test
    void rejectsInvalidPasswordWithoutDisclosingAccountState() throws Exception {
        createUser("manager@example.com", UserRole.MANAGER, false);

        login("manager@example.com", "incorrect password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("Email or password is invalid"));
    }

    @Test
    void requiresCsrfTokenForLogin() throws Exception {
        createUser("manager@example.com", UserRole.MANAGER, false);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("manager@example.com", PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void requiresTemporaryPasswordToBeChangedAndInvalidatesSessionAfterChange() throws Exception {
        createUser("manager@example.com", UserRole.MANAGER, true);
        MvcResult loginResult = login("manager@example.com", PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangeRequired").value(true))
                .andReturn();
        MockHttpSession session = session(loginResult);

        mockMvc.perform(get("/api/system/status").session(session))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/stores").session(session))
                .andExpect(status().isForbidden());
        Cookie csrfCookie = csrfCookie(session);
        mockMvc.perform(post("/api/auth/change-password")
                        .session(session)
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changePasswordJson(PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isNoContent());

        login("manager@example.com", PASSWORD).andExpect(status().isUnauthorized());
        MvcResult secondLogin = login("manager@example.com", NEW_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangeRequired").value(false))
                .andReturn();
        mockMvc.perform(get("/api/system/status").session(session(secondLogin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiContractVersion").value(
                        ApiContractVersion.CURRENT
                ));
    }

    @Test
    void rejectsDisabledUser() throws Exception {
        AppUser user = createUser("disabled@example.com", UserRole.MANAGER, false);
        user.deactivate();
        userRepository.saveAndFlush(user);

        login("disabled@example.com", PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }
    @Test
    void persistsAuditForConfiguredBreakGlassAccountLogin() throws Exception {
        jdbcTemplate.update(
                """
                INSERT INTO app_users (
                    id, email, password_hash, display_name, role, is_active,
                    password_change_required, security_version
                )
                VALUES (?, ?, ?, ?, 'ADMIN', true, false, 0)
                """,
                BREAK_GLASS_USER_ID,
                "break-glass@example.com",
                passwordEncoder.encode(PASSWORD),
                "Emergency Administrator"
        );

        login("break-glass@example.com", PASSWORD)
                .andExpect(status().isOk());

        assertThat(auditLogRepository.findAll()).anySatisfy(audit -> {
            assertThat(audit.getAction()).isEqualTo(
                    AuditAction.BREAK_GLASS_LOGIN_SUCCEEDED.name()
            );
            assertThat(audit.getActorUser().getId())
                    .isEqualTo(BREAK_GLASS_USER_ID);
            assertThat(audit.getEntityId())
                    .isEqualTo(BREAK_GLASS_USER_ID.toString());
        });
    }


    @Test
    void restrictsAdministrativeEndpointToAdministrator() throws Exception {
        createUser("manager@example.com", UserRole.MANAGER, false);
        createUser("admin@example.com", UserRole.ADMIN, false);

        MvcResult managerLogin = login("manager@example.com", PASSWORD).andReturn();
        performInvalidCategoryImport(session(managerLogin))
                .andExpect(status().isForbidden());

        MvcResult adminLogin = login("admin@example.com", PASSWORD).andReturn();
        performInvalidCategoryImport(session(adminLogin))
                .andExpect(status().isBadRequest());
    }

    @Test
    void managerCanReadOnlyAssignedStoreWhileAdministratorCanReadEveryStore() throws Exception {
        Store assignedStore = createStore("assigned");
        Store deniedStore = createStore("denied");
        AppUser manager = createUser("manager@example.com", UserRole.MANAGER, false);
        AppUser administrator = createUser("admin@example.com", UserRole.ADMIN, false);
        accessRepository.save(new UserStoreAccess(manager, assignedStore, administrator));

        MvcResult managerLogin = login("manager@example.com", PASSWORD).andReturn();
        performStoreKpi(session(managerLogin), assignedStore.getId()).andExpect(status().isOk());
        performStoreKpi(session(managerLogin), deniedStore.getId()).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/stores").session(session(managerLogin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(assignedStore.getId().toString()));

        MvcResult adminLogin = login("admin@example.com", PASSWORD).andReturn();
        performStoreKpi(session(adminLogin), deniedStore.getId()).andExpect(status().isOk());
        mockMvc.perform(get("/api/stores").session(session(adminLogin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(assignedStore.getId().toString()))
                .andExpect(jsonPath("$[1].id").value(deniedStore.getId().toString()));
    }

    @Test
    void listsOpaqueSessionsAndRevokesOneOrAllOtherSessions() throws Exception {
        createUser("sessions@example.com", UserRole.MANAGER, false);
        MvcResult firstLogin = login("sessions@example.com", PASSWORD)
                .andExpect(status().isOk())
                .andReturn();
        MvcResult secondLogin = login("sessions@example.com", PASSWORD)
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession firstSession = session(firstLogin);
        MockHttpSession secondSession = session(secondLogin);

        MvcResult listResult = mockMvc.perform(get("/api/auth/sessions")
                        .session(secondSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions.length()").value(2))
                .andExpect(jsonPath("$.sessions[0].current").value(true))
                .andExpect(jsonPath("$.sessions[0].lastSeenAt").exists())
                .andExpect(jsonPath("$.sessions[0].sessionId").doesNotExist())
                .andReturn();
        String body = listResult.getResponse().getContentAsString();
        JsonNode sessions = objectMapper.readTree(body).path("sessions");
        String currentReference = sessions.get(0)
                .path("sessionReference")
                .asString();
        String otherReference = sessions.get(1)
                .path("sessionReference")
                .asString();
        assertThat(currentReference).matches("h1_[0-9a-f]{24}")
                .isNotIn(firstSession.getId(), secondSession.getId());
        assertThat(otherReference).matches("h1_[0-9a-f]{24}")
                .isNotIn(firstSession.getId(), secondSession.getId());
        assertThat(body)
                .doesNotContain("remoteAddress")
                .doesNotContain("userAgent");

        mockMvc.perform(get("/api/auth/sessions"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(delete(
                        "/api/auth/sessions/{sessionReference}",
                        otherReference
                ).session(secondSession))
                .andExpect(status().isForbidden());

        Cookie secondCsrf = csrfCookie(secondSession);
        mockMvc.perform(delete("/api/auth/sessions/{sessionReference}", otherReference)
                        .session(secondSession)
                        .cookie(secondCsrf)
                        .header("X-XSRF-TOKEN", secondCsrf.getValue()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/auth/me").session(firstSession))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
        mockMvc.perform(get("/api/auth/me").session(secondSession))
                .andExpect(status().isOk());

        mockMvc.perform(delete(
                        "/api/auth/sessions/{sessionReference}",
                        "h1_000000000000000000000000"
                )
                        .session(secondSession)
                        .cookie(secondCsrf)
                        .header("X-XSRF-TOKEN", secondCsrf.getValue()))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete(
                        "/api/auth/sessions/{sessionReference}",
                        currentReference
                )
                        .session(secondSession)
                        .cookie(secondCsrf)
                        .header("X-XSRF-TOKEN", secondCsrf.getValue()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(
                        "CURRENT_SESSION_REQUIRES_LOGOUT"
                ));

        MvcResult thirdLogin = login("sessions@example.com", PASSWORD)
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession thirdSession = session(thirdLogin);
        mockMvc.perform(delete("/api/auth/sessions/others")
                        .session(secondSession)
                        .cookie(secondCsrf)
                        .header("X-XSRF-TOKEN", secondCsrf.getValue()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/auth/me").session(thirdSession))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
        mockMvc.perform(get("/api/auth/sessions").session(secondSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions.length()").value(1))
                .andExpect(jsonPath("$.sessions[0].current").value(true));
    }

    private org.springframework.test.web.servlet.ResultActions login(String email, String password)
            throws Exception {
        Cookie csrfCookie = csrfCookie(null);
        return mockMvc.perform(post("/api/auth/login")
                .cookie(csrfCookie)
                .header("X-XSRF-TOKEN", csrfCookie.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginJson(email, password)));
    }

    private org.springframework.test.web.servlet.ResultActions performInvalidCategoryImport(
            MockHttpSession session
    ) throws Exception {
        Cookie csrfCookie = csrfCookie(session);
        return mockMvc.perform(post(
                        "/api/integration-connections/livesklad-default/product-category-imports"
                )
                .session(session)
                .cookie(csrfCookie)
                .header("X-XSRF-TOKEN", csrfCookie.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));
    }

    private org.springframework.test.web.servlet.ResultActions performStoreKpi(
            MockHttpSession session,
            UUID storeId
    ) throws Exception {
        return mockMvc.perform(get("/api/stores/{storeId}/kpi", storeId)
                .session(session)
                .param("periodStart", "2026-01-01")
                .param("periodEnd", "2026-01-01"));
    }

    private Cookie csrfCookie(MockHttpSession session) throws Exception {
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request =
                get("/api/auth/csrf");
        if (session != null) {
            request.session(session);
        }
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private MockHttpSession session(MvcResult result) {
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private AppUser createUser(String email, UserRole role, boolean passwordChangeRequired) {
        AppUser user = new AppUser(email, passwordEncoder.encode(PASSWORD), email, role);
        if (!passwordChangeRequired) {
            user.changePassword(passwordEncoder.encode(PASSWORD));
        }
        return userRepository.saveAndFlush(user);
    }

    private Store createStore(String externalId) {
        Store store = Store.manual(
                externalId,
                externalId,
                null,
                new StoreSchedule(
                        "Europe/Kaliningrad",
                        LocalTime.MIDNIGHT,
                        LocalTime.of(10, 0),
                        LocalTime.of(21, 0)
                )
        );
        return storeRepository.saveAndFlush(store);
    }

    private String loginJson(String email, String password) {
        return """
                {"email":"%s","password":"%s"}
                """.formatted(email, password);
    }

    private String changePasswordJson(String currentPassword, String newPassword) {
        return """
                {"currentPassword":"%s","newPassword":"%s"}
                """.formatted(currentPassword, newPassword);
    }
}
