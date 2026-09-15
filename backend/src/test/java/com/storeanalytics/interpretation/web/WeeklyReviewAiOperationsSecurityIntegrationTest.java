package com.storeanalytics.interpretation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.auth.repository.AppUserRepository;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class WeeklyReviewAiOperationsSecurityIntegrationTest {

    private static final String PASSWORD =
            "weekly review correct horse battery staple";

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void preflightIsRestrictedToAdministrators() throws Exception {
        AppUser manager = createUser(
                "weekly-review-manager@example.test", UserRole.MANAGER
        );
        MockHttpSession managerSession = session(login(manager.getEmail()));
        String endpoint = "/api/admin/weekly-review-ai/snapshots/"
                + UUID.randomUUID() + "/preflight";

        mockMvc.perform(get(endpoint))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(endpoint).session(managerSession))
                .andExpect(status().isForbidden());
    }

    @Test
    void administratorCanReachPreflightWithoutEnqueue() throws Exception {
        AppUser administrator = createUser(
                "weekly-review-admin@example.test", UserRole.ADMIN
        );
        MockHttpSession administratorSession = session(
                login(administrator.getEmail())
        );
        String endpoint = "/api/admin/weekly-review-ai/snapshots/"
                + UUID.randomUUID() + "/preflight";

        mockMvc.perform(get(endpoint).session(administratorSession))
                .andExpect(status().isNotFound());

        mockMvc.perform(post(endpoint.replace("/preflight", "/generate"))
                        .session(administratorSession)
                        .with(csrf()))
                .andExpect(status().isConflict());
    }

    private AppUser createUser(String email, UserRole role) {
        AppUser user = new AppUser(
                email, passwordEncoder.encode(PASSWORD), email, role
        );
        user.changePassword(passwordEncoder.encode(PASSWORD));
        return userRepository.saveAndFlush(user);
    }

    private org.springframework.test.web.servlet.ResultActions login(String email)
            throws Exception {
        Cookie csrfCookie = csrfCookie();
        return mockMvc.perform(post("/api/auth/login")
                .cookie(csrfCookie)
                .header("X-XSRF-TOKEN", csrfCookie.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s"}
                        """.formatted(email, PASSWORD)));
    }

    private Cookie csrfCookie() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private MockHttpSession session(
            org.springframework.test.web.servlet.ResultActions login
    ) throws Exception {
        return (MockHttpSession) login.andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
    }
}
