package com.storeanalytics.auth.security;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.model.UserRole;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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
    private final boolean active;
    private final boolean passwordChangeRequired;
    private final long securityVersion;

    private AppUserPrincipal(AppUser user) {
        userId = user.getId();
        email = user.getEmail();
        passwordHash = user.getPasswordHash();
        displayName = user.getDisplayName();
        role = user.getRole();
        active = user.isActive();
        passwordChangeRequired = user.isPasswordChangeRequired();
        securityVersion = user.getSecurityVersion();
    }

    public static AppUserPrincipal from(AppUser user) {
        return new AppUserPrincipal(user);
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
}
