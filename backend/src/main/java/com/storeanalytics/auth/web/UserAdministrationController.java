package com.storeanalytics.auth.web;

import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.auth.service.AdminUserView;
import com.storeanalytics.auth.service.CreateUserCommand;
import com.storeanalytics.auth.service.UpdateUserCommand;
import com.storeanalytics.auth.service.UserAdministrationService;
import com.storeanalytics.common.web.PageResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users")
public class UserAdministrationController {

    private final UserAdministrationService administrationService;

    public UserAdministrationController(UserAdministrationService administrationService) {
        this.administrationService = administrationService;
    }

    @GetMapping
    PageResponse<AdminUserResponse> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return administrationService.findAll(page, size).map(this::toResponse);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    AdminUserResponse create(
            @Valid @RequestBody CreateUserRequest request,
            Authentication authentication
    ) {
        AdminUserView user = administrationService.create(
                new CreateUserCommand(
                        request.email(),
                        request.temporaryPassword(),
                        request.displayName(),
                        request.role(),
                        request.storeIds()
                ),
                principal(authentication).getUserId()
        );
        return toResponse(user);
    }

    @PutMapping("/{userId}")
    AdminUserResponse update(
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateUserRequest request,
            Authentication authentication
    ) {
        return toResponse(administrationService.update(
                userId,
                new UpdateUserCommand(request.displayName(), request.role(), request.active()),
                principal(authentication).getUserId()
        ));
    }

    @PutMapping("/{userId}/store-access")
    AdminUserResponse replaceStoreAccess(
            @PathVariable UUID userId,
            @Valid @RequestBody StoreAccessRequest request,
            Authentication authentication
    ) {
        return toResponse(administrationService.replaceStoreAccesses(
                userId,
                request.storeIds(),
                principal(authentication).getUserId()
        ));
    }

    @PostMapping("/{userId}/reset-password")
    AdminUserResponse resetPassword(
            @PathVariable UUID userId,
            @Valid @RequestBody ResetPasswordRequest request,
            Authentication authentication
    ) {
        return toResponse(administrationService.resetPassword(
                userId,
                request.temporaryPassword(),
                principal(authentication).getUserId()
        ));
    }

    private AppUserPrincipal principal(Authentication authentication) {
        return (AppUserPrincipal) authentication.getPrincipal();
    }

    private AdminUserResponse toResponse(AdminUserView user) {
        return new AdminUserResponse(
                user.id(),
                user.email(),
                user.displayName(),
                user.role(),
                user.active(),
                user.passwordChangeRequired(),
                user.allStores(),
                user.storeIds(),
                user.lastLoginAt(),
                user.version()
        );
    }
}
