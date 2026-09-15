package com.storeanalytics.auth.security;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.model.UserFeature;
import com.storeanalytics.auth.model.UserRole;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public final class AppUserPrincipal implements UserDetails, CredentialsContainer, Serializable {

    public static final String PASSWORD_CHANGE_REQUIRED_AUTHORITY = "PASSWORD_CHANGE_REQUIRED";

    private final UUID userId;
    private final String email;
    private String passwordHash;
    private final String displayName;
    private final UserRole role;
    private final Set<UserFeature> features;
    private final boolean active;
    private final boolean passwordChangeRequired;
    private final long securityVersion;

    private AppUserPrincipal(AppUser user, Collection<UserFeature> grantedFeatures) {
        userId = user.getId();
        email = user.getEmail();
        passwordHash = user.getPasswordHash();
        displayName = user.getDisplayName();
        role = user.getRole();
        features = role == UserRole.ADMIN
                ? Set.copyOf(java.util.EnumSet.allOf(UserFeature.class))
                : Set.copyOf(grantedFeatures);
        active = user.isActive();
        passwordChangeRequired = user.isPasswordChangeRequired();
        securityVersion = user.getSecurityVersion();
    }

    public static AppUserPrincipal from(AppUser user) {
        return new AppUserPrincipal(user, List.of());
    }

    public static AppUserPrincipal from(
            AppUser user,
            Collection<UserFeature> grantedFeatures
    ) {
        return new AppUserPrincipal(user, grantedFeatures);
    }

    public UUID getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public UserRole getRole() {
        return role;
    }

    public Set<UserFeature> getFeatures() {
        return features;
    }

    public boolean hasFeature(UserFeature feature) {
        return features.contains(feature);
    }

    public boolean isPasswordChangeRequired() {
        return passwordChangeRequired;
    }

    public long getSecurityVersion() {
        return securityVersion;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
        features.stream()
                .map(feature -> new SimpleGrantedAuthority("FEATURE_" + feature.name()))
                .forEach(authorities::add);
        if (passwordChangeRequired) {
            authorities.add(new SimpleGrantedAuthority(PASSWORD_CHANGE_REQUIRED_AUTHORITY));
        }
        return List.copyOf(authorities);
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }

    @Override
    public void eraseCredentials() {
        passwordHash = null;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof AppUserPrincipal principal
                && Objects.equals(userId, principal.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId);
    }
}
