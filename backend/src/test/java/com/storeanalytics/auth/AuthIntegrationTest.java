package com.storeanalytics.auth;

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
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.model.StoreSchedule;
import com.storeanalytics.store.repository.StoreRepository;
import jakarta.servlet.http.Cookie;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.mock.web.MockHttpSession;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class AuthIntegrationTest {

    private static final String PASSWORD = "correct horse battery staple";
    private static final String NEW_PASSWORD = "passw0rd-2026";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private UserStoreAccessRepository accessRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
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
                .andExpect(status().isOk());
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
