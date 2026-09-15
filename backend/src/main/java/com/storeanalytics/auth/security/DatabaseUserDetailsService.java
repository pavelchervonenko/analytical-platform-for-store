package com.storeanalytics.auth.security;

import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.auth.repository.UserFeatureAccessRepository;
import java.util.Locale;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final AppUserRepository userRepository;
    private final UserFeatureAccessRepository featureAccessRepository;

    public DatabaseUserDetailsService(
            AppUserRepository userRepository,
            UserFeatureAccessRepository featureAccessRepository
    ) {
        this.userRepository = userRepository;
        this.featureAccessRepository = featureAccessRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        String normalizedEmail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        return userRepository.findByEmailIgnoreCase(normalizedEmail)
                .map(user -> AppUserPrincipal.from(
                        user,
                        featureAccessRepository.findAllByIdUserId(user.getId()).stream()
                                .map(access -> access.getId().getFeature())
                                .toList()
                ))
                .orElseThrow(() -> new UsernameNotFoundException("Invalid email or password"));
    }
}
