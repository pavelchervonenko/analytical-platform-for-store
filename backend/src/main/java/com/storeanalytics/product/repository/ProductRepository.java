package com.storeanalytics.product.repository;

import com.storeanalytics.product.model.Product;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    Optional<Product> findByConnectionIdAndExternalId(UUID connectionId, String externalId);
}
