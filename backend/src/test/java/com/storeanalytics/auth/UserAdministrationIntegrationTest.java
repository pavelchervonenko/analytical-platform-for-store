package com.storeanalytics.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.model.UserFeature;
import com.storeanalytics.auth.model.UserFeatureAccess;
import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.auth.model.UserStoreAccess;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.auth.repository.UserFeatureAccessRepository;
import com.storeanalytics.auth.repository.UserStoreAccessRepository;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.model.StoreSchedule;
import com.storeanalytics.store.repository.StoreRepository;
import jakarta.servlet.http.Cookie;
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
class UserAdministrationIntegrationTest {

    private static final String ADMIN_PASSWORD = "admin correct horse battery staple";
    private static final String MANAGER_PASSWORD = "passw0rd-2026";

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
    private UserFeatureAccessRepository featureAccessRepository;

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
        featureAccessRepository.deleteAll();
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
                                  "storeIds": ["%s"],
                                  "features": ["PLAN", "SHIFTS"]
                                }
                                """.formatted(MANAGER_PASSWORD, store.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("manager@example.com"))
                .andExpect(jsonPath("$.passwordChangeRequired").value(true))
                .andExpect(jsonPath("$.storeIds[0]").value(store.getId().toString()))
                .andExpect(jsonPath("$.features[0]").value("PLAN"))
                .andExpect(jsonPath("$.features[1]").value("SHIFTS"))
                .andExpect(jsonPath("$.temporaryPassword").doesNotExist());

        login("manager@example.com", MANAGER_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangeRequired").value(true))
                .andExpect(jsonPath("$.features[0]").value("PLAN"))
                .andExpect(jsonPath("$.features[1]").value("SHIFTS"));
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
                                  "active": false,
                                  "storeIds": [],
                                  "features": [],
                                  "version": %d
                                }
                                """.formatted(currentVersion(manager))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(get("/api/auth/me").session(managerSession))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        assertThat(administrator.isActive()).isTrue();
    }

    @Test
    void featureChangeInvalidatesExistingSessionAndNoOpPreservesReplacementSession()
            throws Exception {
        AppUser administrator = createUser(
                "admin@example.com",
                ADMIN_PASSWORD,
                UserRole.ADMIN
        );
        AppUser manager = createUser(
                "manager@example.com",
                MANAGER_PASSWORD,
                UserRole.MANAGER
        );
        featureAccessRepository.saveAndFlush(
                new UserFeatureAccess(manager, UserFeature.PAYROLL, administrator)
        );
        MockHttpSession managerSession = session(login(
                "manager@example.com",
                MANAGER_PASSWORD
        ).andExpect(jsonPath("$.features[0]").value("PAYROLL")));
        MockHttpSession adminSession = session(login("admin@example.com", ADMIN_PASSWORD));
        Cookie csrfCookie = csrfCookie(adminSession);

        long changedVersion = updateManagerFeatures(
                manager,
                adminSession,
                csrfCookie,
                "PLAN"
        );

        mockMvc.perform(get("/api/auth/me").session(managerSession))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        MockHttpSession replacementSession = session(login(
                "manager@example.com",
                MANAGER_PASSWORD
        ).andExpect(jsonPath("$.features[0]").value("PLAN")));
        updateManagerFeatures(
                userRepository.findById(manager.getId()).orElseThrow(),
                adminSession,
                csrfCookie,
                "PLAN"
        );

        mockMvc.perform(get("/api/auth/me").session(replacementSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.features[0]").value("PLAN"));
        assertThat(changedVersion).isGreaterThan(manager.getVersion());
    }

    @Test
    void staleCombinedAccessUpdateIsRejected() throws Exception {
        createUser("admin@example.com", ADMIN_PASSWORD, UserRole.ADMIN);
        AppUser manager = createUser(
                "manager@example.com",
                MANAGER_PASSWORD,
                UserRole.MANAGER
        );
        MockHttpSession adminSession = session(login("admin@example.com", ADMIN_PASSWORD));
        Cookie csrfCookie = csrfCookie(adminSession);
        long initialVersion = currentVersion(manager);

        mockMvc.perform(put("/api/admin/users/{userId}", manager.getId())
                        .session(adminSession)
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Updated manager",
                                  "role": "MANAGER",
                                  "active": true,
                                  "storeIds": [],
                                  "features": ["PLAN"],
                                  "version": %d
                                }
                                """.formatted(initialVersion)))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/admin/users/{userId}", manager.getId())
                        .session(adminSession)
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Stale update",
                                  "role": "MANAGER",
                                  "active": true,
                                  "storeIds": [],
                                  "features": ["PAYROLL"],
                                  "version": %d
                                }
                                """.formatted(initialVersion)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));

        assertThat(featureAccessRepository.findAllByIdUserId(manager.getId()))
                .extracting(access -> access.getId().getFeature())
                .containsExactly(UserFeature.PLAN);
    }

    @Test
    void roleTransitionsClearAndRestoreExplicitAccessAtomically() throws Exception {
        AppUser administrator = createUser(
                "admin@example.com",
                ADMIN_PASSWORD,
                UserRole.ADMIN
        );
        AppUser manager = createUser(
                "manager@example.com",
                MANAGER_PASSWORD,
                UserRole.MANAGER
        );
        Store store = createStore("store-one");
        accessRepository.saveAndFlush(new UserStoreAccess(manager, store, administrator));
        featureAccessRepository.saveAndFlush(
                new UserFeatureAccess(manager, UserFeature.PLAN, administrator)
        );
        MockHttpSession adminSession = session(login("admin@example.com", ADMIN_PASSWORD));
        Cookie csrfCookie = csrfCookie(adminSession);

        MvcResult promotion = mockMvc.perform(put("/api/admin/users/{userId}", manager.getId())
                        .session(adminSession)
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Promoted administrator",
                                  "role": "ADMIN",
                                  "active": true,
                                  "storeIds": [],
                                  "features": [],
                                  "version": %d
                                }
                                """.formatted(currentVersion(manager))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allStores").value(true))
                .andExpect(jsonPath("$.storeIds").isEmpty())
                .andExpect(jsonPath("$.features.length()").value(3))
                .andReturn();

        assertThat(accessRepository.findAllByIdUserId(manager.getId())).isEmpty();
        assertThat(featureAccessRepository.findAllByIdUserId(manager.getId())).isEmpty();

        mockMvc.perform(put("/api/admin/users/{userId}", manager.getId())
                        .session(adminSession)
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Manager again",
                                  "role": "MANAGER",
                                  "active": true,
                                  "storeIds": ["%s"],
                                  "features": ["PAYROLL"],
                                  "version": %d
                                }
                                """.formatted(store.getId(), responseVersion(promotion))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allStores").value(false))
                .andExpect(jsonPath("$.storeIds[0]").value(store.getId().toString()))
                .andExpect(jsonPath("$.features[0]").value("PAYROLL"));

        assertThat(accessRepository.findAllByIdUserId(manager.getId()))
                .extracting(access -> access.getId().getStoreId())
                .containsExactly(store.getId());
        assertThat(featureAccessRepository.findAllByIdUserId(manager.getId()))
                .extracting(access -> access.getId().getFeature())
                .containsExactly(UserFeature.PAYROLL);
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
                                  "active": false,
                                  "storeIds": [],
                                  "features": [],
                                  "version": %d
                                }
                                """.formatted(currentVersion(administrator))))
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

    private long updateManagerFeatures(
            AppUser manager,
            MockHttpSession adminSession,
            Cookie csrfCookie,
            String feature
    ) throws Exception {
        MvcResult result = mockMvc.perform(put("/api/admin/users/{userId}", manager.getId())
                        .session(adminSession)
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Manager",
                                  "role": "MANAGER",
                                  "active": true,
                                  "storeIds": [],
                                  "features": ["%s"],
                                  "version": %d
                                }
                                """.formatted(feature, currentVersion(manager))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.features[0]").value(feature))
                .andReturn();
        return responseVersion(result);
    }

    private long currentVersion(AppUser user) {
        return userRepository.findById(user.getId()).orElseThrow().getVersion();
    }

    private long responseVersion(MvcResult result) throws java.io.UnsupportedEncodingException {
        return Long.parseLong(com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(),
                "$.version"
        ).toString());
    }
}
