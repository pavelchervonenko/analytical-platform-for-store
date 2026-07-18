package com.storeanalytics.employee.repository;

import com.storeanalytics.employee.model.Employee;
import com.storeanalytics.sync.model.SourceSystem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmployeeRepository extends JpaRepository<Employee, UUID> {

    Optional<Employee> findByConnectionIdAndExternalId(UUID connectionId, String externalId);

    List<Employee> findAllByConnectionId(UUID connectionId);

    Optional<Employee> findBySourceSystemAndExternalId(
            SourceSystem sourceSystem,
            String externalId
    );
}
