package com.storeanalytics.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.auth.security.NfcPasswordEncoder;
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
        PasswordEncoder delegate = mock(PasswordEncoder.class);
        NfcPasswordEncoder encoder = new NfcPasswordEncoder(delegate);
        when(repository.findById(userId)).thenReturn(Optional.of(user));
        when(delegate.upgradeEncoding("{bcrypt}legacy")).thenReturn(true);
        when(delegate.encode("authenticated password")).thenReturn("{bcrypt}strong");

        AuthenticationService service = new AuthenticationService(
                repository,
                encoder,
                new PasswordPolicy(password -> false),
                Clock.systemUTC()
        );

        service.recordSuccessfulLogin(userId, "authenticated password");

        assertThat(user.getPasswordHash()).isEqualTo("{bcrypt}strong");
        assertThat(user.getLastLoginAt()).isNotNull();
    }
}
