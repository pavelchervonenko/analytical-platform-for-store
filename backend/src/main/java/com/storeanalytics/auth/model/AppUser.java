package com.storeanalytics.auth.model;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import com.storeanalytics.common.persistence.AbstractMutableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;

@Entity
@Table(name = "app_users")
public class AppUser extends AbstractMutableEntity {

    @Column(nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "password_change_required", nullable = false)
    private boolean passwordChangeRequired;

    @Column(name = "security_version", nullable = false)
    private long securityVersion;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected AppUser() {
    }

    public AppUser(String email, String passwordHash, String displayName, UserRole role) {
        this.email = normalizeEmail(email);
        this.passwordHash = requireText(passwordHash, "passwordHash");
        this.displayName = requireText(displayName, "displayName");
        this.role = requireNonNull(role, "role");
        this.active = true;
        this.passwordChangeRequired = true;
        this.securityVersion = 0;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public UserRole getRole() {
        return role;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isPasswordChangeRequired() {
        return passwordChangeRequired;
    }

    public long getSecurityVersion() {
        return securityVersion;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public void updateProfile(String newDisplayName, UserRole newRole) {
        displayName = requireText(newDisplayName, "displayName");
        UserRole requiredRole = requireNonNull(newRole, "role");
        if (role != requiredRole) {
            role = requiredRole;
            securityVersion++;
        }
    }

    public void resetPassword(String newPasswordHash) {
        passwordHash = requireText(newPasswordHash, "passwordHash");
        passwordChangeRequired = true;
        securityVersion++;
    }

    public void changePassword(String newPasswordHash) {
        passwordHash = requireText(newPasswordHash, "passwordHash");
        passwordChangeRequired = false;
        securityVersion++;
    }

    public void recordSuccessfulLogin(Instant loginAt) {
        lastLoginAt = requireNonNull(loginAt, "loginAt");
    }

    public void activate() {
        if (!active) {
            active = true;
            securityVersion++;
        }
    }

    public void deactivate() {
        if (active) {
            active = false;
            securityVersion++;
        }
    }

    private static String normalizeEmail(String value) {
        return requireText(value, "email").trim().toLowerCase(Locale.ROOT);
    }
}
