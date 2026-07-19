package com.storeanalytics.sync.service;

import com.storeanalytics.employee.model.Employee;
import com.storeanalytics.employee.model.EmployeeStoreAssignment;
import com.storeanalytics.employee.model.EmployeeStoreAssignmentId;
import com.storeanalytics.employee.repository.EmployeeRepository;
import com.storeanalytics.employee.repository.EmployeeStoreAssignmentRepository;
import com.storeanalytics.integration.livesklad.dto.LiveSkladEmployeePayload;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.repository.StoreRepository;
import com.storeanalytics.sync.model.RawRecordVersion;
import com.storeanalytics.sync.model.SourceSystem;
import com.storeanalytics.sync.model.SyncRun;
import com.storeanalytics.sync.repository.RawRecordVersionRepository;
import com.storeanalytics.sync.repository.SyncRunRepository;
import com.storeanalytics.sync.support.JsonPayloadHasher;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class EmployeeSyncPersistence {

    private static final String EMPLOYEE_ENTITY_TYPE = "EMPLOYEE";

    private final EmployeeRepository employeeRepository;
    private final EmployeeStoreAssignmentRepository assignmentRepository;
    private final StoreRepository storeRepository;
    private final SyncRunRepository syncRunRepository;
    private final RawRecordVersionRepository rawRecordRepository;
    private final JsonPayloadHasher payloadHasher;
    private final Clock clock;

    public EmployeeSyncPersistence(
            EmployeeRepository employeeRepository,
            EmployeeStoreAssignmentRepository assignmentRepository,
            StoreRepository storeRepository,
            SyncRunRepository syncRunRepository,
            RawRecordVersionRepository rawRecordRepository,
            JsonPayloadHasher payloadHasher,
            Clock clock
    ) {
        this.employeeRepository = employeeRepository;
        this.assignmentRepository = assignmentRepository;
        this.storeRepository = storeRepository;
        this.syncRunRepository = syncRunRepository;
        this.rawRecordRepository = rawRecordRepository;
        this.payloadHasher = payloadHasher;
        this.clock = clock;
    }

    @Transactional
    public EmployeeRecordWriteResult synchronize(
            UUID syncRunId,
            UUID storeId,
            LiveSkladEmployeePayload source
    ) {
        validate(source);
        Instant now = clock.instant();
        SyncRun syncRun = syncRunRepository.findById(syncRunId)
                .orElseThrow(() -> new IllegalArgumentException("syncRun does not exist"));
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new IllegalArgumentException("store does not exist"));
        if (!store.isConnectedTo(syncRun.getConnection())) {
            throw new IllegalArgumentException(
                    "store and employee sync run must belong to the same connection"
            );
        }

        String hash = payloadHasher.sha256(source.rawPayload());
        Optional<RawRecordVersion> existingVersion = rawRecordRepository.findStoreRecordVersion(
                syncRun.getConnection().getId(),
                store.getId(),
                SourceSystem.LIVESKLAD.name(),
                EMPLOYEE_ENTITY_TYPE,
                source.externalId(),
                hash
        );
        RawRecordVersion rawVersion;
        if (existingVersion.isPresent()) {
            rawVersion = existingVersion.get();
            rawVersion.markSeen(syncRun, now);
        } else {
            rawVersion = RawRecordVersion.pendingEmployee(
                    store,
                    source.externalId(),
                    payloadHasher.serialize(source.rawPayload()),
                    hash,
                    syncRun,
                    now
            );
            rawRecordRepository.save(rawVersion);
        }

        EmployeeRecordWriteResult result = normalizeEmployee(
                syncRun,
                store,
                source
        );
        if (!rawVersion.isNormalized()) {
            rawVersion.markNormalized(now);
        }
        return result;
    }

    @Transactional
    public int deactivateMissingAssignments(UUID storeId, Set<UUID> seenEmployeeIds) {
        Set<UUID> seen = Set.copyOf(seenEmployeeIds);
        int deactivated = 0;
        for (EmployeeStoreAssignment assignment : assignmentRepository.findAllByStoreId(storeId)) {
            if (assignment.isActive() && !seen.contains(assignment.getEmployee().getId())) {
                assignment.update(false, assignment.participatesInRanking());
                deactivated++;
            }
        }
        return deactivated;
    }

    @Transactional
    public int deactivateMissingEmployees(UUID connectionId, Set<UUID> seenEmployeeIds) {
        Set<UUID> seen = Set.copyOf(seenEmployeeIds);
        int deactivated = 0;
        for (Employee employee : employeeRepository.findAllByConnectionId(connectionId)) {
            if (employee.isActive() && !seen.contains(employee.getId())) {
                employee.updateFromLiveSklad(
                        employee.getFullName(),
                        false,
                        employee.getSourceUpdatedAt()
                );
                deactivated++;
            }
        }
        return deactivated;
    }

    private EmployeeRecordWriteResult normalizeEmployee(
            SyncRun syncRun,
            Store store,
            LiveSkladEmployeePayload source
    ) {
        Optional<Employee> existingEmployee =
                employeeRepository.findByConnectionIdAndExternalId(
                        syncRun.getConnection().getId(),
                        source.externalId()
                );
        boolean employeeCreated = existingEmployee.isEmpty();
        Employee employee = existingEmployee.orElseGet(() -> employeeRepository.save(
                Employee.fromLiveSklad(
                        syncRun.getConnection(),
                        source.externalId(),
                        source.fullName(),
                        null
                )
        ));
        boolean employeeChanged = !employeeCreated
                && employee.updateFromLiveSklad(source.fullName(), true, null);

        EmployeeStoreAssignmentId assignmentId =
                new EmployeeStoreAssignmentId(employee.getId(), store.getId());
        Optional<EmployeeStoreAssignment> existingAssignment =
                assignmentRepository.findById(assignmentId);
        boolean assignmentCreated = existingAssignment.isEmpty();
        boolean assignmentChanged = false;
        if (assignmentCreated) {
            assignmentRepository.save(new EmployeeStoreAssignment(employee, store, false));
        } else {
            EmployeeStoreAssignment assignment = existingAssignment.get();
            assignmentChanged = assignment.update(true, assignment.participatesInRanking());
        }

        StoreWriteResult outcome;
        if (employeeCreated || assignmentCreated) {
            outcome = StoreWriteResult.CREATED;
        } else if (employeeChanged || assignmentChanged) {
            outcome = StoreWriteResult.UPDATED;
        } else {
            outcome = StoreWriteResult.SKIPPED;
        }
        return new EmployeeRecordWriteResult(employee.getId(), outcome);
    }

    private void validate(LiveSkladEmployeePayload source) {
        if (source == null
                || !StringUtils.hasText(source.externalId())
                || !StringUtils.hasText(source.fullName())
                || source.rawPayload() == null) {
            throw new IllegalArgumentException("LiveSklad employee payload is incomplete");
        }
    }
}
