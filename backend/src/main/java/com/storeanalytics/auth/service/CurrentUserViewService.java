package com.storeanalytics.auth.service;

import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.auth.repository.UserStoreAccessRepository;
import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.auth.web.CurrentUserResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CurrentUserViewService {

    private final UserStoreAccessRepository accessRepository;

    public CurrentUserViewService(UserStoreAccessRepository accessRepository) {
        this.accessRepository = accessRepository;
    }

    @Transactional(readOnly = true)
    public CurrentUserResponse create(AppUserPrincipal principal) {
        boolean allStores = principal.getRole() == UserRole.ADMIN;
        List<UUID> storeIds = allStores
                ? List.of()
                : accessRepository.findAllByIdUserId(principal.getUserId()).stream()
                        .map(access -> access.getId().getStoreId())
                        .sorted()
                        .toList();
        return new CurrentUserResponse(
                principal.getUserId(),
                principal.getEmail(),
                principal.getDisplayName(),
                principal.getRole(),
                principal.isPasswordChangeRequired(),
                allStores,
                storeIds
        );
    }
}
