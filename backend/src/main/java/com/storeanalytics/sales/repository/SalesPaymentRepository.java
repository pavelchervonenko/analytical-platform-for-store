package com.storeanalytics.sales.repository;

import com.storeanalytics.sales.model.SalesPayment;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SalesPaymentRepository extends JpaRepository<SalesPayment, UUID> {

    List<SalesPayment> findAllBySalesDocumentId(UUID salesDocumentId);
}
