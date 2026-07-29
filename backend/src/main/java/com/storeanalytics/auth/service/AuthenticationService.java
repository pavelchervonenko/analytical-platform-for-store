package com.storeanalytics.auth.service;

import com.storeanalytics.auth.exception.InvalidCurrentPasswordException;
import com.storeanalytics.auth.exception.PasswordPolicyViolationException;
import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.auth.security.NfcPasswordEncoder;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthenticationService {

    private final AppUserRepository userRepository;
    private final NfcPasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final Clock clock;

    public AuthenticationService(
            AppUserRepository userRepository,
            NfcPasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy,
            Clock clock
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.clock = clock;
    }

    @Transactional
    public void recordSuccessfulLogin(UUID userId, String authenticatedPassword) {
        AppUser user = requireUser(userId);
        if (passwordEncoder.requiresUpgradeAfterSuccessfulMatch(authenticatedPassword, user.getPasswordHash())) {
            user.upgradePasswordHash(passwordEncoder.encode(authenticatedPassword));
        }
        user.recordSuccessfulLogin(Instant.now(clock));
    }

    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        AppUser user = requireUser(userId);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new InvalidCurrentPasswordException();
        }
        passwordPolicy.validate(newPassword);
        if (PasswordCanonicalizer.canonicalize(currentPassword)
                .equals(PasswordCanonicalizer.canonicalize(newPassword))
                || passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new PasswordPolicyViolationException("New password must differ from the current password");
        }
        user.changePassword(passwordEncoder.encode(newPassword));
    }

    private AppUser requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("Authenticated user no longer exists"));
    }
}
