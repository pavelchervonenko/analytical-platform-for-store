package com.storeanalytics.salary.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.common.exception.InvalidRequestException;
import com.storeanalytics.product.repository.ProductRepository;
import com.storeanalytics.salary.model.PayrollCategoryCode;
import com.storeanalytics.salary.repository.ProductPayrollCategoryAssignmentRepository;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PayrollBulkClassificationServiceResourceLimitTest {

    private ProductRepository productRepository;
    private ProductPayrollCategoryAssignmentRepository assignmentRepository;
    private AppUserRepository userRepository;
    private AuditLogService auditLogService;
    private PayrollBulkClassificationService service;

    @BeforeEach
    void setUp() {
        productRepository = mock(ProductRepository.class);
        assignmentRepository = mock(
                ProductPayrollCategoryAssignmentRepository.class
        );
        userRepository = mock(AppUserRepository.class);
        auditLogService = mock(AuditLogService.class);
        service = new PayrollBulkClassificationService(
                productRepository,
                assignmentRepository,
                userRepository,
                auditLogService
        );
    }

    @Test
    void rejectsOversizedBatchBeforeDatabaseAccess() {
        PayrollProductCategoryChange change = new PayrollProductCategoryChange(
                UUID.randomUUID(),
                PayrollCategoryCode.ACCESSORY
        );
        List<PayrollProductCategoryChange> changes = Collections.nCopies(
                PayrollBulkClassificationService.MAXIMUM_ASSIGNMENTS + 1,
                change
        );

        assertThatThrownBy(() -> service.assign(
                LocalDate.of(2026, 8, 1),
                "resource boundary",
                changes,
                UUID.randomUUID()
        ))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("no more than 500");

        verifyNoInteractions(
                productRepository,
                assignmentRepository,
                userRepository,
                auditLogService
        );
    }

    @Test
    void rejectsOversizedReasonBeforeDatabaseAccess() {
        assertThatThrownBy(() -> service.assign(
                LocalDate.of(2026, 8, 1),
                "x".repeat(
                        PayrollBulkClassificationService.MAXIMUM_REASON_LENGTH + 1
                ),
                List.of(new PayrollProductCategoryChange(
                        UUID.randomUUID(),
                        PayrollCategoryCode.ACCESSORY
                )),
                UUID.randomUUID()
        ))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("no more than 2000");

        verifyNoInteractions(
                productRepository,
                assignmentRepository,
                userRepository,
                auditLogService
        );
    }
}
