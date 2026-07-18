package com.storeanalytics.auth.repository;

import com.storeanalytics.auth.model.UserStoreAccess;
import com.storeanalytics.auth.model.UserStoreAccessId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserStoreAccessRepository extends JpaRepository<UserStoreAccess, UserStoreAccessId> {
}
