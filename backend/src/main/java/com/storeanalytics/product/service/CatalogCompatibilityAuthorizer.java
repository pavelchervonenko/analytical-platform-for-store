package com.storeanalytics.product.service;

import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.auth.security.AppUserPrincipal;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Server-side check also protects internal calls which do not pass through an HTTP controller. */
@Component
public class CatalogCompatibilityAuthorizer {
    private final AppUserRepository users;

    public CatalogCompatibilityAuthorizer(AppUserRepository users) {
        this.users = users;
    }

    public UUID requireAdministrator() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AppUserPrincipal principal)
                || principal.getRole() != UserRole.ADMIN || !principal.isEnabled()
                || principal.isPasswordChangeRequired()) {
            throw denied();
        }
        var user = users.findById(principal.getUserId()).orElseThrow(this::denied);
        if (!user.isActive() || user.getRole() != UserRole.ADMIN || user.isPasswordChangeRequired()
                || user.getSecurityVersion() != principal.getSecurityVersion()) {
            throw denied();
        }
        return user.getId();
    }

    private AccessDeniedException denied() {
        return new AccessDeniedException("Global catalog administration is required");
    }
}
