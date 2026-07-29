package com.storeanalytics.auth.service;

import com.storeanalytics.audit.service.AuditAction;
import com.storeanalytics.audit.service.AuditEntityType;
import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.audit.service.AuditTarget;
import com.storeanalytics.auth.exception.ManagedUserNotFoundException;
import com.storeanalytics.auth.exception.UserAdministrationConflictException;
import com.storeanalytics.auth.exception.UserEmailConflictException;
import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.auth.model.UserStoreAccess;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.auth.repository.UserStoreAccessRepository;
import com.storeanalytics.common.web.PageParameters;
import com.storeanalytics.common.web.PageResponse;
import com.storeanalytics.common.security.SecurityAuditLogger;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.repository.StoreRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Sort;
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
    private final SecurityAuditLogger securityAuditLogger;
    private final AuditLogService auditLogService;

    public UserAdministrationService(
            AppUserRepository userRepository,
            UserStoreAccessRepository accessRepository,
            StoreRepository storeRepository,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy,
            SecurityAuditLogger securityAuditLogger,
            AuditLogService auditLogService
    ) {
        this.userRepository = userRepository;
        this.accessRepository = accessRepository;
        this.storeRepository = storeRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.securityAuditLogger = securityAuditLogger;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminUserView> findAll(int page, int size) {
        var users = userRepository.findAdminPage(
                new PageParameters(page, size).pageable(Sort.unsorted())
        );
        Map<UUID, List<UUID>> storeIdsByUser = accessRepository
                .findAllByIdUserIdIn(users.stream().map(AppUser::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(
                        access -> access.getId().getUserId(),
                        Collectors.mapping(
                                access -> access.getId().getStoreId(),
                                Collectors.collectingAndThen(
                                        Collectors.toList(),
                                        ids -> ids.stream().sorted().toList()
                                )
                        )
                ));
        return PageResponse.from(users.map(user -> createView(
                user, storeIdsByUser.getOrDefault(user.getId(), List.of())
        )));
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
        AdminUserView result = createView(user);
        auditLogService.record(
                actorId,
                null,
                AuditAction.USER_CREATED,
                new AuditTarget(AuditEntityType.USER, user.getId()),
                null,
                null,
                userSummary(result)
        );
        securityAuditLogger.userAdministration("create", actorId, user.getId());
        return result;
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
        Map<String, Object> before = userSummary(createView(user));
        user.updateProfile(command.displayName(), command.role());
        if (command.active()) {
            user.activate();
        } else {
            user.deactivate();
        }
        if (command.role() == UserRole.ADMIN) {
            removeStoreAccesses(user.getId());
        }
        AdminUserView result = createView(user);
        auditLogService.record(
                actorId,
                null,
                AuditAction.USER_CHANGED,
                new AuditTarget(AuditEntityType.USER, userId),
                null,
                before,
                userSummary(result)
        );
        securityAuditLogger.userAdministration("update", actorId, userId);
        return result;
    }

    @Transactional
    public AdminUserView replaceStoreAccesses(UUID userId, Set<UUID> storeIds, UUID actorId) {
        AppUser user = requireUser(userId);
        AppUser actor = requireUser(actorId);
        Map<String, Object> before = userSummary(createView(user));
        replaceStoreAccesses(user, storeIds, actor);
        AdminUserView result = createView(user);
        auditLogService.record(
                actorId,
                null,
                AuditAction.USER_STORE_ACCESS_CHANGED,
                new AuditTarget(AuditEntityType.USER, userId),
                null,
                before,
                userSummary(result)
        );
        securityAuditLogger.userAdministration("replace_store_access", actorId, userId);
        return result;
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
        Map<String, Object> before = userSummary(createView(user));
        user.resetPassword(passwordEncoder.encode(temporaryPassword));
        AdminUserView result = createView(user);
        auditLogService.record(
                actorId,
                null,
                AuditAction.USER_PASSWORD_RESET,
                new AuditTarget(AuditEntityType.USER, userId),
                null,
                before,
                userSummary(result)
        );
        securityAuditLogger.userAdministration("reset_password", actorId, userId);
        return result;
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
                && userRepository.findAllActiveByRoleForUpdate(UserRole.ADMIN).size() <= 1) {
            throw new UserAdministrationConflictException(
                    "The last active administrator cannot be demoted or deactivated"
            );
        }
    }

    private AdminUserView createView(AppUser user) {
        List<UUID> storeIds = accessRepository.findAllByIdUserId(user.getId()).stream()
                .map(access -> access.getId().getStoreId())
                .sorted()
                .toList();
        return createView(user, storeIds);
    }

    private AdminUserView createView(AppUser user, List<UUID> assignedStoreIds) {
        boolean allStores = user.getRole() == UserRole.ADMIN;
        return new AdminUserView(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRole(),
                user.isActive(),
                user.isPasswordChangeRequired(),
                allStores,
                allStores ? List.of() : assignedStoreIds,
                user.getLastLoginAt(),
                user.getVersion()
        );
    }

    private Map<String, Object> userSummary(AdminUserView user) {
        return Map.of(
                "displayName", user.displayName(),
                "role", user.role(),
                "active", user.active(),
                "passwordChangeRequired", user.passwordChangeRequired(),
                "allStores", user.allStores(),
                "storeIds", user.storeIds(),
                "version", user.version()
        );
    }

    private AppUser requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ManagedUserNotFoundException(userId));
    }
}
