package com.storeanalytics.auth.repository;

import com.storeanalytics.auth.model.UserFeatureAccess;
import com.storeanalytics.auth.model.UserFeatureAccessId;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserFeatureAccessRepository
        extends JpaRepository<UserFeatureAccess, UserFeatureAccessId> {

    List<UserFeatureAccess> findAllByIdUserId(UUID userId);

    List<UserFeatureAccess> findAllByIdUserIdIn(Collection<UUID> userIds);

    void deleteAllByIdUserId(UUID userId);
}
