package com.storeanalytics.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.auth.security.AppUserPrincipal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class CatalogCompatibilityAuthorizerTest {
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final CatalogCompatibilityAuthorizer authorizer = new CatalogCompatibilityAuthorizer(users);
    private final UUID actor = UUID.randomUUID();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsAnonymousWithoutReadingUsers() {
        assertThatThrownBy(authorizer::requireAdministrator).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(users);
    }

    @Test
    void acceptsOnlyCurrentActiveAdministrator() {
        AppUser user = user();
        authenticate(user);
        when(users.findById(actor)).thenReturn(Optional.of(user));
        assertThat(authorizer.requireAdministrator()).isEqualTo(actor);
    }

    @ParameterizedTest
    @ValueSource(strings = {"manager", "inactive", "password", "version", "deleted"})
    void rejectsPrivilegesRevokedAfterAuthentication(String change) {
        AppUser user = user();
        authenticate(user);
        when(users.findById(actor)).thenReturn(Optional.of(user));
        switch (change) {
            case "manager" -> when(user.getRole()).thenReturn(UserRole.MANAGER);
            case "inactive" -> when(user.isActive()).thenReturn(false);
            case "password" -> when(user.isPasswordChangeRequired()).thenReturn(true);
            case "version" -> when(user.getSecurityVersion()).thenReturn(2L);
            case "deleted" -> when(users.findById(actor)).thenReturn(Optional.empty());
            default -> throw new IllegalArgumentException(change);
        }
        assertThatThrownBy(authorizer::requireAdministrator).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void storeManagerDoesNotReceiveGlobalCatalogWriteAuthority() {
        AppUser user = user();
        when(user.getRole()).thenReturn(UserRole.MANAGER);
        authenticate(user);
        assertThatThrownBy(authorizer::requireAdministrator).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(users);
    }

    private AppUser user() {
        AppUser user = mock(AppUser.class);
        when(user.getId()).thenReturn(actor);
        when(user.getRole()).thenReturn(UserRole.ADMIN);
        when(user.isActive()).thenReturn(true);
        when(user.getSecurityVersion()).thenReturn(1L);
        return user;
    }

    private void authenticate(AppUser user) {
        var principal = AppUserPrincipal.from(user);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }
}
