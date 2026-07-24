package com.storeanalytics.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.auth.repository.AppUserRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthenticationServiceTest {

    @Test
    void upgradesLegacyPasswordHashAfterSuccessfulAuthentication() {
        UUID userId = UUID.randomUUID();
        AppUser user = new AppUser(
                "manager@example.com",
                "{bcrypt}legacy",
                "Manager",
                UserRole.MANAGER
        );
        AppUserRepository repository = mock(AppUserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(repository.findById(userId)).thenReturn(Optional.of(user));
        when(encoder.upgradeEncoding("{bcrypt}legacy")).thenReturn(true);
        when(encoder.encode("authenticated password")).thenReturn("{bcrypt}strong");

        AuthenticationService service = new AuthenticationService(
                repository,
                encoder,
                new PasswordPolicy(),
                Clock.systemUTC()
        );

        service.recordSuccessfulLogin(userId, "authenticated password");

        assertThat(user.getPasswordHash()).isEqualTo("{bcrypt}strong");
        assertThat(user.getLastLoginAt()).isNotNull();
    }
}
