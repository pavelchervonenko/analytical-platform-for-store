package com.storeanalytics.product.repository;

import com.storeanalytics.product.model.Product;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    long countByConnectionId(UUID connectionId);

    Optional<Product> findByConnectionIdAndExternalId(UUID connectionId, String externalId);

    List<Product> findAllByConnectionIdAndCode(UUID connectionId, String code);
}
