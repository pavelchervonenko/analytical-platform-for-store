package com.storeanalytics.store.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.store.service.StoreCatalogService;
import com.storeanalytics.store.service.StoreSummaryView;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StoreDirectoryControllerTest {

    private StoreCatalogService storeCatalogService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        storeCatalogService = mock(StoreCatalogService.class);
        JsonMapper objectMapper = JsonMapper.builder()
                .findAndAddModules()
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new StoreDirectoryController(storeCatalogService))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void returnsAccessibleStoreContract() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        AppUser user = mock(AppUser.class);
        when(user.getId()).thenReturn(userId);
        when(user.getEmail()).thenReturn("manager@example.com");
        when(user.getPasswordHash()).thenReturn("{bcrypt}hash");
        when(user.getDisplayName()).thenReturn("Руководитель");
        when(user.getRole()).thenReturn(UserRole.MANAGER);
        when(user.isActive()).thenReturn(true);
        AppUserPrincipal principal = AppUserPrincipal.from(user);
        when(storeCatalogService.findAccessible(userId, UserRole.MANAGER)).thenReturn(List.of(
                new StoreSummaryView(
                        storeId,
                        "Future Store",
                        "Ленинский проспект, 30",
                        "Europe/Kaliningrad",
                        LocalTime.MIDNIGHT,
                        LocalTime.of(10, 0),
                        LocalTime.of(21, 0),
                        true
                )
        ));

        mockMvc.perform(get("/api/stores").principal(
                        UsernamePasswordAuthenticationToken.authenticated(
                                principal, null, principal.getAuthorities()
                        )
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(storeId.toString()))
                .andExpect(jsonPath("$[0].name").value("Future Store"))
                .andExpect(jsonPath("$[0].address").value("Ленинский проспект, 30"))
                .andExpect(jsonPath("$[0].timezone").value("Europe/Kaliningrad"))
                .andExpect(jsonPath("$[0].businessDayStart").value("00:00:00"))
                .andExpect(jsonPath("$[0].opensAt").value("10:00:00"))
                .andExpect(jsonPath("$[0].closesAt").value("21:00:00"))
                .andExpect(jsonPath("$[0].active").value(true));

        verify(storeCatalogService).findAccessible(userId, UserRole.MANAGER);
    }
}
