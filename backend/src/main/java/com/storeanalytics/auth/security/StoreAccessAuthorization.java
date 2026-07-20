package com.storeanalytics.auth.security;

import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.auth.repository.UserStoreAccessRepository;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public class StoreAccessAuthorization {

    private final UserStoreAccessRepository accessRepository;

    public StoreAccessAuthorization(UserStoreAccessRepository accessRepository) {
        this.accessRepository = accessRepository;
    }

    public boolean canAccess(UUID storeId, Authentication authentication) {
        if (storeId == null
                || authentication == null
                || !(authentication.getPrincipal() instanceof AppUserPrincipal principal)) {
            return false;
        }
        return principal.getRole() == UserRole.ADMIN
                || accessRepository.existsByIdUserIdAndIdStoreId(principal.getUserId(), storeId);
    }
}
