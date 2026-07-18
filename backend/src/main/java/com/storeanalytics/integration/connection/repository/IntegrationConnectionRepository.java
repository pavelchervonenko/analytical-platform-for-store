package com.storeanalytics.integration.connection.repository;

import com.storeanalytics.integration.connection.model.IntegrationConnection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IntegrationConnectionRepository extends JpaRepository<IntegrationConnection, UUID> {

    Optional<IntegrationConnection> findByConnectionKeyAndActiveTrue(String connectionKey);
}
