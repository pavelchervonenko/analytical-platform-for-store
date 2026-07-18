package com.storeanalytics.auth.repository;

import com.storeanalytics.auth.model.AppUser;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
}
