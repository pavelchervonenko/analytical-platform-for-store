package com.storeanalytics.performance.service;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.audit.service.AuditAction;
import com.storeanalytics.audit.service.AuditEntityType;
import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.audit.service.AuditTarget;
import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.metrics.exception.StoreNotFoundException;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.performance.exception.RatingPeriodNotClosedException;
import com.storeanalytics.performance.model.EmployeeRatingSnapshot;
import com.storeanalytics.performance.repository.EmployeeRatingSnapshotRepository;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.repository.StoreRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmployeeRatingFinalizationService {

    private final EmployeeRatingService ratingService;
    private final EmployeeRatingSnapshotRepository snapshotRepository;
    private final EmployeeRatingSnapshotCodec snapshotCodec;
    private final StoreRepository storeRepository;
    private final AppUserRepository userRepository;
    private final Clock clock;
    private final AuditLogService auditLogService;

    public EmployeeRatingFinalizationService(
            EmployeeRatingService ratingService,
            EmployeeRatingSnapshotRepository snapshotRepository,
            EmployeeRatingSnapshotCodec snapshotCodec,
            StoreRepository storeRepository,
            AppUserRepository userRepository,
            Clock clock,
            AuditLogService auditLogService
    ) {
        this.ratingService = ratingService;
        this.snapshotRepository = snapshotRepository;
        this.snapshotCodec = snapshotCodec;
        this.storeRepository = storeRepository;
        this.userRepository = userRepository;
        this.clock = clock;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public EmployeeRatingResult finalizePeriod(
            UUID storeId,
            StoreKpiPeriod period,
            UUID actorId
    ) {
        UUID validatedStoreId = requireNonNull(storeId, "storeId");
        StoreKpiPeriod validatedPeriod = requireNonNull(period, "period");
        Store store = storeRepository.findByIdForUpdate(validatedStoreId)
                .orElseThrow(() -> new StoreNotFoundException(validatedStoreId));

        EmployeeRatingSnapshot existing = snapshotRepository
                .findByStoreIdAndPeriodStartAndPeriodEnd(
                        validatedStoreId,
                        validatedPeriod.start(),
                        validatedPeriod.end()
                ).orElse(null);
        if (existing != null) {
            return snapshotCodec.decode(existing);
        }

        LocalDate currentDate = LocalDate.now(
                clock.withZone(ZoneId.of(store.getTimezone()))
        );
        if (!validatedPeriod.end().isBefore(currentDate)) {
            throw new RatingPeriodNotClosedException(validatedPeriod.end(), currentDate);
        }

        AppUser actor = userRepository.findById(requireNonNull(actorId, "actorId"))
                .orElseThrow(() -> new IllegalArgumentException("actor does not exist"));
        EmployeeRatingResult result = ratingService.calculate(
                validatedStoreId, validatedPeriod
        );
        String payload = snapshotCodec.encode(result);
        EmployeeRatingSnapshot snapshot = snapshotRepository.saveAndFlush(
                new EmployeeRatingSnapshot(
                        store,
                        validatedPeriod.start(),
                        validatedPeriod.end(),
                        result.formula().version(),
                        payload,
                        snapshotCodec.sha256(payload),
                        actor
                )
        );
        auditLogService.record(
                actorId,
                store.getId(),
                AuditAction.EMPLOYEE_RATING_FINALIZED,
                new AuditTarget(AuditEntityType.EMPLOYEE_RATING_SNAPSHOT, snapshot.getId()),
                null,
                null,
                Map.of(
                        "periodStart", validatedPeriod.start(),
                        "periodEnd", validatedPeriod.end(),
                        "formulaVersion", result.formula().version(),
                        "snapshotHash", snapshotCodec.sha256(payload)
                )
        );
        return snapshotCodec.decode(snapshot);
    }
}
