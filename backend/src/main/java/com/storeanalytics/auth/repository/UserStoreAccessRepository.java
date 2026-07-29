package com.storeanalytics.auth.repository;

import com.storeanalytics.auth.model.UserStoreAccess;
import com.storeanalytics.auth.model.UserStoreAccessId;
import com.storeanalytics.store.model.Store;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserStoreAccessRepository extends JpaRepository<UserStoreAccess, UserStoreAccessId> {

    boolean existsByIdUserIdAndIdStoreId(UUID userId, UUID storeId);

    List<UserStoreAccess> findAllByIdUserId(UUID userId);

    List<UserStoreAccess> findAllByIdUserIdIn(Collection<UUID> userIds);

    @Query("""
            select access.store from UserStoreAccess access
            where access.id.userId = :userId and access.store.active = true
            """)
    List<Store> findActiveStoresByUserId(@Param("userId") UUID userId);

    void deleteAllByIdUserId(UUID userId);
}
