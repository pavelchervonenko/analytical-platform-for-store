package com.storeanalytics.auth.repository;

import com.storeanalytics.auth.model.UserStoreAccess;
import com.storeanalytics.auth.model.UserStoreAccessId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserStoreAccessRepository extends JpaRepository<UserStoreAccess, UserStoreAccessId> {

    boolean existsByIdUserIdAndIdStoreId(UUID userId, UUID storeId);

    List<UserStoreAccess> findAllByIdUserId(UUID userId);

    void deleteAllByIdUserId(UUID userId);
}
