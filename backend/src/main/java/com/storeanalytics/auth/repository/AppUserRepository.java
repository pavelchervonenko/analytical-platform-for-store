package com.storeanalytics.auth.repository;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.model.UserRole;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    long countByRoleAndActiveTrue(UserRole role);

    List<AppUser> findAllByOrderByDisplayNameAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select user
            from AppUser user
            where user.role = :role and user.active = true
            order by user.id
            """)
    List<AppUser> findAllActiveByRoleForUpdate(@Param("role") UserRole role);
}
