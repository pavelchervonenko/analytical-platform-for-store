package com.storeanalytics.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.sync.service.SyncJobStateMetrics;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class SecurityHardeningIntegrationTest {

    private static final String EMAIL = "security@example.com";
    private static final String PASSWORD = "correct horse battery staple";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SyncJobStateMetrics syncJobStateMetrics;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM auth_login_throttles");
        userRepository.deleteAll();
    }

    @Test
    void blocksLoginAfterConfiguredNumberOfFailures() throws Exception {
        createUser();

        for (int attempt = 0; attempt < 5; attempt++) {
            login(EMAIL, "wrong password")
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }

        login(EMAIL, "wrong password")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.code").value("LOGIN_THROTTLED"));
    }

    @Test
    void deniesAuthenticatedRequestsToUnlistedApiRoutes() throws Exception {
        createUser();
        MockHttpSession session = session(login(EMAIL, PASSWORD));

        mockMvc.perform(get("/api/unlisted").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void expiresOldestSessionWhenConcurrentSessionLimitIsExceeded() throws Exception {
        createUser();
        MockHttpSession firstSession = session(login(EMAIL, PASSWORD));
        session(login(EMAIL, PASSWORD));
        session(login(EMAIL, PASSWORD));
        session(login(EMAIL, PASSWORD));
        mockMvc.perform(get("/api/auth/me").session(firstSession))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
    }

    @Test
    void exposesSafePublicHealthAndBuildInformation() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.build.name").value("backend"))
                .andExpect(jsonPath("$.build.version").value("0.1.0-SNAPSHOT"))
                .andExpect(jsonPath("$.git").doesNotExist());
    }

    @Test
    void rejectsCorsPreflightFromUnknownOrigin() throws Exception {
        mockMvc.perform(options("/api/auth/login")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void exposesCustomMetricsOnlyToAdministrators() throws Exception {
        createUser(UserRole.ADMIN);
        syncJobStateMetrics.refresh();
        MockHttpSession session = session(login(EMAIL, PASSWORD));

        mockMvc.perform(get("/actuator/metrics/storeanalytics.sync.jobs")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("storeanalytics.sync.jobs"))
                .andExpect(jsonPath(
                        "$.availableTags[?(@.tag == 'status')]").isNotEmpty());
    }

    private org.springframework.test.web.servlet.ResultActions login(String email, String password)
            throws Exception {
        Cookie csrfCookie = csrfCookie();
        return mockMvc.perform(post("/api/auth/login")
                .cookie(csrfCookie)
                .header("X-XSRF-TOKEN", csrfCookie.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s"}
                        """.formatted(email, password)));
    }

    private Cookie csrfCookie() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private MockHttpSession session(org.springframework.test.web.servlet.ResultActions login)
            throws Exception {
        return (MockHttpSession) login.andExpect(status().isOk())
                .andReturn()
                .getRequest()
                .getSession(false);
    }

    private void createUser() {
        createUser(UserRole.MANAGER);
    }

    private void createUser(UserRole role) {
        AppUser user = new AppUser(
                EMAIL,
                passwordEncoder.encode(PASSWORD),
                "Security User",
                role
        );
        user.changePassword(passwordEncoder.encode(PASSWORD));
        userRepository.saveAndFlush(user);
    }
}
