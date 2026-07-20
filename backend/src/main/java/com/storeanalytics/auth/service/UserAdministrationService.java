package com.storeanalytics.auth.service;

import com.storeanalytics.auth.exception.ManagedUserNotFoundException;
import com.storeanalytics.auth.exception.UserAdministrationConflictException;
import com.storeanalytics.auth.exception.UserEmailConflictException;
import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.auth.model.UserStoreAccess;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.auth.repository.UserStoreAccessRepository;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.repository.StoreRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserAdministrationService {

    private final AppUserRepository userRepository;
    private final UserStoreAccessRepository accessRepository;
    private final StoreRepository storeRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;

    public UserAdministrationService(
            AppUserRepository userRepository,
            UserStoreAccessRepository accessRepository,
            StoreRepository storeRepository,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy
    ) {
        this.userRepository = userRepository;
        this.accessRepository = accessRepository;
        this.storeRepository = storeRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
    }

    @Transactional(readOnly = true)
    public List<AdminUserView> findAll() {
        return userRepository.findAllByOrderByDisplayNameAsc().stream()
                .map(this::createView)
                .toList();
    }

    @Transactional
    public AdminUserView create(CreateUserCommand command, UUID actorId) {
        if (userRepository.existsByEmailIgnoreCase(command.email())) {
            throw new UserEmailConflictException(command.email());
        }
        passwordPolicy.validate(command.temporaryPassword());
        AppUser actor = requireUser(actorId);
        AppUser user = new AppUser(
                command.email(),
                passwordEncoder.encode(command.temporaryPassword()),
                command.displayName(),
                command.role()
        );
        userRepository.save(user);
        replaceStoreAccesses(user, command.storeIds(), actor);
        return createView(user);
    }

    @Transactional
    public AdminUserView update(UUID userId, UpdateUserCommand command, UUID actorId) {
        AppUser user = requireUser(userId);
        if (userId.equals(actorId)
                && (command.role() != user.getRole() || !command.active())) {
            throw new UserAdministrationConflictException(
                    "Administrator cannot change their own role or deactivate their own account"
            );
        }
        protectLastAdministrator(user, command.role(), command.active());
        user.updateProfile(command.displayName(), command.role());
        if (command.active()) {
            user.activate();
        } else {
            user.deactivate();
        }
        if (command.role() == UserRole.ADMIN) {
            removeStoreAccesses(user.getId());
        }
        return createView(user);
    }

    @Transactional
    public AdminUserView replaceStoreAccesses(UUID userId, Set<UUID> storeIds, UUID actorId) {
        AppUser user = requireUser(userId);
        AppUser actor = requireUser(actorId);
        replaceStoreAccesses(user, storeIds, actor);
        return createView(user);
    }

    @Transactional
    public AdminUserView resetPassword(UUID userId, String temporaryPassword, UUID actorId) {
        if (userId.equals(actorId)) {
            throw new UserAdministrationConflictException(
                    "Use the change-password endpoint to change your own password"
            );
        }
        passwordPolicy.validate(temporaryPassword);
        AppUser user = requireUser(userId);
        user.resetPassword(passwordEncoder.encode(temporaryPassword));
        return createView(user);
    }

    private void replaceStoreAccesses(AppUser user, Set<UUID> requestedStoreIds, AppUser actor) {
        Set<UUID> storeIds = requestedStoreIds == null ? Set.of() : Set.copyOf(requestedStoreIds);
        if (user.getRole() == UserRole.ADMIN) {
            if (!storeIds.isEmpty()) {
                throw new UserAdministrationConflictException(
                        "Administrators automatically have access to all stores"
                );
            }
            removeStoreAccesses(user.getId());
            return;
        }

        List<Store> stores = storeRepository.findAllById(storeIds);
        Set<UUID> foundStoreIds = new HashSet<>();
        stores.forEach(store -> foundStoreIds.add(store.getId()));
        if (!foundStoreIds.equals(storeIds)) {
            throw new UserAdministrationConflictException("One or more stores do not exist");
        }

        removeStoreAccesses(user.getId());
        accessRepository.saveAll(stores.stream()
                .map(store -> new UserStoreAccess(user, store, actor))
                .toList());
    }

    private void removeStoreAccesses(UUID userId) {
        accessRepository.deleteAllByIdUserId(userId);
        accessRepository.flush();
    }

    private void protectLastAdministrator(AppUser user, UserRole newRole, boolean newActive) {
        boolean removesActiveAdministrator = user.getRole() == UserRole.ADMIN
                && user.isActive()
                && (newRole != UserRole.ADMIN || !newActive);
        if (removesActiveAdministrator
                && userRepository.countByRoleAndActiveTrue(UserRole.ADMIN) <= 1) {
            throw new UserAdministrationConflictException(
                    "The last active administrator cannot be demoted or deactivated"
            );
        }
    }

    private AdminUserView createView(AppUser user) {
        boolean allStores = user.getRole() == UserRole.ADMIN;
        List<UUID> storeIds = allStores
                ? List.of()
                : accessRepository.findAllByIdUserId(user.getId()).stream()
                        .map(access -> access.getId().getStoreId())
                        .sorted()
                        .toList();
        return new AdminUserView(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRole(),
                user.isActive(),
                user.isPasswordChangeRequired(),
                allStores,
                storeIds,
                user.getLastLoginAt(),
                user.getVersion()
        );
    }

    private AppUser requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ManagedUserNotFoundException(userId));
    }
}
