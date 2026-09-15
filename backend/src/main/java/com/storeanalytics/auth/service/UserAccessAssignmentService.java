package com.storeanalytics.auth.service;

import com.storeanalytics.auth.exception.UserAdministrationConflictException;
import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.model.UserFeature;
import com.storeanalytics.auth.model.UserFeatureAccess;
import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.auth.model.UserStoreAccess;
import com.storeanalytics.auth.repository.UserFeatureAccessRepository;
import com.storeanalytics.auth.repository.UserStoreAccessRepository;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.repository.StoreRepository;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class UserAccessAssignmentService {

    private final UserStoreAccessRepository storeAccessRepository;
    private final UserFeatureAccessRepository featureAccessRepository;
    private final StoreRepository storeRepository;

    public UserAccessAssignmentService(
            UserStoreAccessRepository storeAccessRepository,
            UserFeatureAccessRepository featureAccessRepository,
            StoreRepository storeRepository
    ) {
        this.storeAccessRepository = storeAccessRepository;
        this.featureAccessRepository = featureAccessRepository;
        this.storeRepository = storeRepository;
    }

    public Map<UUID, List<UUID>> storeIdsByUser(Collection<UUID> userIds) {
        return storeAccessRepository.findAllByIdUserIdIn(userIds).stream()
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
    }

    public Map<UUID, List<UserFeature>> featuresByUser(Collection<UUID> userIds) {
        return featureAccessRepository.findAllByIdUserIdIn(userIds).stream()
                .collect(Collectors.groupingBy(
                        access -> access.getId().getUserId(),
                        Collectors.mapping(
                                access -> access.getId().getFeature(),
                                Collectors.collectingAndThen(
                                        Collectors.toList(),
                                        features -> features.stream().sorted().toList()
                                )
                        )
                ));
    }

    public Set<UUID> assignedStoreIds(UUID userId) {
        return storeAccessRepository.findAllByIdUserId(userId).stream()
                .map(access -> access.getId().getStoreId())
                .collect(Collectors.toUnmodifiableSet());
    }

    public Set<UserFeature> assignedFeatures(UUID userId) {
        return featureAccessRepository.findAllByIdUserId(userId).stream()
                .map(access -> access.getId().getFeature())
                .collect(Collectors.toUnmodifiableSet());
    }

    public void replaceStoreAccesses(
            AppUser user,
            Set<UUID> requestedStoreIds,
            AppUser actor
    ) {
        Set<UUID> storeIds = Set.copyOf(requestedStoreIds);
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
        storeAccessRepository.saveAll(stores.stream()
                .map(store -> new UserStoreAccess(user, store, actor))
                .toList());
    }

    public void replaceFeatureAccesses(
            AppUser user,
            Set<UserFeature> requestedFeatures,
            AppUser actor
    ) {
        Set<UserFeature> features = Set.copyOf(requestedFeatures);
        if (user.getRole() == UserRole.ADMIN) {
            if (!features.isEmpty()) {
                throw new UserAdministrationConflictException(
                        "Administrators automatically have access to all features"
                );
            }
            removeFeatureAccesses(user.getId());
            return;
        }
        removeFeatureAccesses(user.getId());
        featureAccessRepository.saveAll(features.stream()
                .sorted()
                .map(feature -> new UserFeatureAccess(user, feature, actor))
                .toList());
    }

    private void removeStoreAccesses(UUID userId) {
        storeAccessRepository.deleteAllByIdUserId(userId);
        storeAccessRepository.flush();
    }

    private void removeFeatureAccesses(UUID userId) {
        featureAccessRepository.deleteAllByIdUserId(userId);
        featureAccessRepository.flush();
    }
}
