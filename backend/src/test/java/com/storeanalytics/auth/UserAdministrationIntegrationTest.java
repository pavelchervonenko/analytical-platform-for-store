package com.storeanalytics.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.auth.repository.UserStoreAccessRepository;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.model.StoreSchedule;
import com.storeanalytics.store.repository.StoreRepository;
import jakarta.servlet.http.Cookie;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
class UserAdministrationIntegrationTest {

    private static final String ADMIN_PASSWORD = "admin correct horse battery staple";
    private static final String MANAGER_PASSWORD = "passw0rd";

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
    void administratorCreatesManagerWithTemporaryPasswordAndStoreAccess() throws Exception {
        createUser("admin@example.com", ADMIN_PASSWORD, UserRole.ADMIN);
        Store store = createStore("store-one");
        MockHttpSession adminSession = session(login("admin@example.com", ADMIN_PASSWORD));
        Cookie csrfCookie = csrfCookie(adminSession);

        mockMvc.perform(post("/api/admin/users")
                        .session(adminSession)
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "manager@example.com",
                                  "temporaryPassword": "%s",
                                  "displayName": "Manager",
                                  "role": "MANAGER",
                                  "storeIds": ["%s"]
                                }
                                """.formatted(MANAGER_PASSWORD, store.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("manager@example.com"))
                .andExpect(jsonPath("$.passwordChangeRequired").value(true))
                .andExpect(jsonPath("$.storeIds[0]").value(store.getId().toString()))
                .andExpect(jsonPath("$.temporaryPassword").doesNotExist());

        login("manager@example.com", MANAGER_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangeRequired").value(true));
    }

    @Test
    void deactivationImmediatelyInvalidatesExistingManagerSession() throws Exception {
        AppUser administrator = createUser("admin@example.com", ADMIN_PASSWORD, UserRole.ADMIN);
        AppUser manager = createUser("manager@example.com", MANAGER_PASSWORD, UserRole.MANAGER);
        MockHttpSession managerSession = session(login("manager@example.com", MANAGER_PASSWORD));
        MockHttpSession adminSession = session(login("admin@example.com", ADMIN_PASSWORD));
        Cookie csrfCookie = csrfCookie(adminSession);

        mockMvc.perform(put("/api/admin/users/{userId}", manager.getId())
                        .session(adminSession)
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Manager",
                                  "role": "MANAGER",
                                  "active": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(get("/api/auth/me").session(managerSession))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        assertThat(administrator.isActive()).isTrue();
    }

    @Test
    void administratorCannotDeactivateOwnAccount() throws Exception {
        AppUser administrator = createUser("admin@example.com", ADMIN_PASSWORD, UserRole.ADMIN);
        MockHttpSession adminSession = session(login("admin@example.com", ADMIN_PASSWORD));
        Cookie csrfCookie = csrfCookie(adminSession);

        mockMvc.perform(put("/api/admin/users/{userId}", administrator.getId())
                        .session(adminSession)
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Administrator",
                                  "role": "ADMIN",
                                  "active": false
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_ADMINISTRATION_CONFLICT"));
    }

    private org.springframework.test.web.servlet.ResultActions login(String email, String password)
            throws Exception {
        Cookie csrfCookie = csrfCookie(null);
        return mockMvc.perform(post("/api/auth/login")
                .cookie(csrfCookie)
                .header("X-XSRF-TOKEN", csrfCookie.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s"}
                        """.formatted(email, password)));
    }

    private Cookie csrfCookie(MockHttpSession session) throws Exception {
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request =
                get("/api/auth/csrf");
        if (session != null) {
            request.session(session);
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
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

    private AppUser createUser(String email, String password, UserRole role) {
        AppUser user = new AppUser(email, passwordEncoder.encode(password), email, role);
        user.changePassword(passwordEncoder.encode(password));
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
