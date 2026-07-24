package com.storeanalytics.performance.service;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.audit.service.AuditAction;
import com.storeanalytics.audit.service.AuditEntityType;
import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.audit.service.AuditTarget;
import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.performance.exception.RatingSchemeConflictException;
import com.storeanalytics.performance.exception.RatingSchemeNotFoundException;
import com.storeanalytics.performance.model.RatingScheme;
import com.storeanalytics.performance.model.RatingSchemeDefinition;
import com.storeanalytics.performance.repository.RatingSchemeRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RatingSchemeService {

    private final RatingSchemeRepository schemeRepository;
    private final AppUserRepository userRepository;
    private final AuditLogService auditLogService;

    public RatingSchemeService(
            RatingSchemeRepository schemeRepository,
            AppUserRepository userRepository,
            AuditLogService auditLogService
    ) {
        this.schemeRepository = schemeRepository;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public List<RatingSchemeView> findAll() {
        return schemeRepository.findAllByOrderByEffectiveFromDesc().stream()
                .map(this::toView)
                .toList();
    }

    @Transactional
    public RatingSchemeView create(
            String code,
            LocalDate effectiveFrom,
            RatingSchemeDefinition definition,
            UUID actorId
    ) {
        if (schemeRepository.existsByCode(code)) {
            throw new RatingSchemeConflictException("Rating scheme code already exists");
        }
        LocalDate validatedDate = requireNonNull(effectiveFrom, "effectiveFrom");
        if (schemeRepository.existsByEffectiveFrom(validatedDate)) {
            throw new RatingSchemeConflictException(
                    "A rating scheme already starts on the requested date"
            );
        }
        AppUser actor = userRepository.findById(requireNonNull(actorId, "actorId"))
                .orElseThrow(() -> new IllegalArgumentException("actor does not exist"));
        RatingScheme scheme = new RatingScheme(code, validatedDate, definition, actor);
        RatingScheme saved = schemeRepository.saveAndFlush(scheme);
        auditLogService.record(
                actorId,
                null,
                AuditAction.RATING_SCHEME_CREATED,
                new AuditTarget(AuditEntityType.RATING_SCHEME, saved.getId()),
                null,
                null,
                schemeSummary(saved)
        );
        return toView(saved);
    }

    @Transactional(readOnly = true)
    public RatingScheme effectiveOn(LocalDate date) {
        LocalDate validated = requireNonNull(date, "date");
        return schemeRepository
                .findFirstByEffectiveFromLessThanEqualOrderByEffectiveFromDesc(validated)
                .orElseThrow(() -> new RatingSchemeNotFoundException(
                        "No rating scheme is effective for the requested period"
                ));
    }

    private Map<String, Object> schemeSummary(RatingScheme scheme) {
        return Map.ofEntries(
                Map.entry("code", scheme.getCode()),
                Map.entry("effectiveFrom", scheme.getEffectiveFrom()),
                Map.entry("contributionWeight", scheme.getContributionWeight()),
                Map.entry("efficiencyWeight", scheme.getEfficiencyWeight()),
                Map.entry("structureWeight", scheme.getStructureWeight()),
                Map.entry("attachWeight", scheme.getAttachWeight()),
                Map.entry("accessoryStructureWeight", scheme.getAccessoryStructureWeight()),
                Map.entry("serviceStructureWeight", scheme.getServiceStructureWeight()),
                Map.entry("minimumAttachDenominator", scheme.getMinimumAttachDenominator()),
                Map.entry("scoreCap", scheme.getScoreCap()),
                Map.entry("minimumCoveragePercent", scheme.getMinimumCoveragePercent())
        );
    }

    private RatingSchemeView toView(RatingScheme scheme) {
        return new RatingSchemeView(
                scheme.getId(),
                scheme.getCode(),
                scheme.getEffectiveFrom(),
                scheme.getContributionWeight(),
                scheme.getEfficiencyWeight(),
                scheme.getStructureWeight(),
                scheme.getAttachWeight(),
                scheme.getAccessoryStructureWeight(),
                scheme.getServiceStructureWeight(),
                scheme.getMinimumAttachDenominator(),
                scheme.getScoreCap(),
                scheme.getMinimumCoveragePercent(),
                scheme.getCreatedBy() == null ? null : scheme.getCreatedBy().getId(),
                scheme.getCreatedAt()
        );
    }
}
