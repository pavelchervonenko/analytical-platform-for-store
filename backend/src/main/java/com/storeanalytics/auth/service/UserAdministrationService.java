package com.storeanalytics.auth.service;

import com.storeanalytics.audit.service.AuditAction;
import com.storeanalytics.audit.service.AuditEntityType;
import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.audit.service.AuditTarget;
import com.storeanalytics.auth.exception.ManagedUserNotFoundException;
import com.storeanalytics.auth.exception.UserAdministrationConflictException;
import com.storeanalytics.auth.exception.UserEmailConflictException;
import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.model.UserFeature;
import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.common.security.SecurityAuditLogger;
import com.storeanalytics.common.web.PageParameters;
import com.storeanalytics.common.web.PageResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserAdministrationService {

    private final AppUserRepository userRepository;
    private final UserAccessAssignmentService accessAssignmentService;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final SecurityAuditLogger securityAuditLogger;
    private final AuditLogService auditLogService;

    public UserAdministrationService(
            AppUserRepository userRepository,
            UserAccessAssignmentService accessAssignmentService,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy,
            SecurityAuditLogger securityAuditLogger,
            AuditLogService auditLogService
    ) {
        this.userRepository = userRepository;
        this.accessAssignmentService = accessAssignmentService;
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
        List<UUID> userIds = users.stream().map(AppUser::getId).toList();
        Map<UUID, List<UUID>> storeIdsByUser = accessAssignmentService
                .storeIdsByUser(userIds);
        Map<UUID, List<UserFeature>> featuresByUser = accessAssignmentService
                .featuresByUser(userIds);
        return PageResponse.from(users.map(user -> createView(
                user,
                storeIdsByUser.getOrDefault(user.getId(), List.of()),
                featuresByUser.getOrDefault(user.getId(), List.of())
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
        accessAssignmentService.replaceStoreAccesses(user, command.storeIds(), actor);
        accessAssignmentService.replaceFeatureAccesses(user, command.features(), actor);
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
        AppUser user = requireUserForUpdate(userId);
        requireCurrentVersion(user, command.version());
        if (userId.equals(actorId)
                && (command.role() != user.getRole() || !command.active())) {
            throw new UserAdministrationConflictException(
                    "Administrator cannot change their own role or deactivate their own account"
            );
        }
        protectLastAdministrator(user, command.role(), command.active());
        Map<String, Object> before = userSummary(createView(user));
        UserRole previousRole = user.getRole();
        Set<UUID> previousStoreIds = accessAssignmentService.assignedStoreIds(user.getId());
        Set<UserFeature> previousFeatures = accessAssignmentService.assignedFeatures(user.getId());
        Set<UUID> requestedStoreIds = Set.copyOf(command.storeIds());
        Set<UserFeature> requestedFeatures = Set.copyOf(command.features());
        validateAdministratorAccess(command.role(), requestedStoreIds, requestedFeatures);
        Set<UUID> desiredStoreIds = command.role() == UserRole.ADMIN
                ? Set.of()
                : requestedStoreIds;
        Set<UserFeature> desiredFeatures = command.role() == UserRole.ADMIN
                ? Set.of()
                : requestedFeatures;
        boolean accessChanged = !previousStoreIds.equals(desiredStoreIds)
                || !previousFeatures.equals(desiredFeatures);
        user.updateProfile(command.displayName(), command.role());
        if (command.active()) {
            user.activate();
        } else {
            user.deactivate();
        }
        AppUser actor = requireUser(actorId);
        if (!previousStoreIds.equals(desiredStoreIds)) {
            accessAssignmentService.replaceStoreAccesses(user, desiredStoreIds, actor);
        }
        if (!previousFeatures.equals(desiredFeatures)) {
            accessAssignmentService.replaceFeatureAccesses(user, desiredFeatures, actor);
        }
        if (accessChanged && previousRole == command.role()) {
            user.recordAccessPolicyChange();
        }
        userRepository.flush();
        AdminUserView result = createView(user);
        auditLogService.record(
                actorId,
                null,
                accessChanged ? AuditAction.USER_ACCESS_CHANGED : AuditAction.USER_CHANGED,
                new AuditTarget(AuditEntityType.USER, userId),
                null,
                before,
                userSummary(result)
        );
        securityAuditLogger.userAdministration("update", actorId, userId);
        return result;
    }

    @Transactional
    public AdminUserView replaceStoreAccesses(
            UUID userId,
            Set<UUID> storeIds,
            long version,
            UUID actorId
    ) {
        AppUser user = requireUserForUpdate(userId);
        requireCurrentVersion(user, version);
        AppUser actor = requireUser(actorId);
        Map<String, Object> before = userSummary(createView(user));
        Set<UUID> previousStoreIds = accessAssignmentService.assignedStoreIds(userId);
        Set<UUID> desiredStoreIds = Set.copyOf(storeIds);
        if (!previousStoreIds.equals(desiredStoreIds)) {
            accessAssignmentService.replaceStoreAccesses(user, desiredStoreIds, actor);
            user.recordAccessPolicyChange();
        }
        userRepository.flush();
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
        userRepository.flush();
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

    private void validateAdministratorAccess(
            UserRole role,
            Set<UUID> storeIds,
            Set<UserFeature> features
    ) {
        if (role == UserRole.ADMIN && (!storeIds.isEmpty() || !features.isEmpty())) {
            throw new UserAdministrationConflictException(
                    "Administrators automatically have access to all stores and features"
            );
        }
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
        List<UUID> storeIds = accessAssignmentService.assignedStoreIds(user.getId()).stream()
                .sorted()
                .toList();
        List<UserFeature> features = accessAssignmentService.assignedFeatures(user.getId()).stream()
                .sorted()
                .toList();
        return createView(user, storeIds, features);
    }

    private AdminUserView createView(
            AppUser user,
            List<UUID> assignedStoreIds,
            List<UserFeature> assignedFeatures
    ) {
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
                allStores
                        ? java.util.EnumSet.allOf(UserFeature.class).stream().toList()
                        : assignedFeatures,
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
                "features", user.features(),
                "version", user.version()
        );
    }

    private void requireCurrentVersion(AppUser user, long expectedVersion) {
        if (user.getVersion() != expectedVersion) {
            throw new ObjectOptimisticLockingFailureException(AppUser.class, user.getId());
        }
    }

    private AppUser requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ManagedUserNotFoundException(userId));
    }

    private AppUser requireUserForUpdate(UUID userId) {
        return userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ManagedUserNotFoundException(userId));
    }
}
