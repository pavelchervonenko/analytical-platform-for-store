package com.storeanalytics.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeanalytics.audit.service.AuditAction;
import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.common.exception.PreconditionFailedException;
import com.storeanalytics.common.exception.PreconditionRequiredException;
import com.storeanalytics.common.idempotency.IdempotencyKeyConflictException;
import com.storeanalytics.common.idempotency.IdempotencyProperties;
import com.storeanalytics.common.idempotency.IdempotencyReceipt;
import com.storeanalytics.common.idempotency.IdempotencyReceiptRepository;
import com.storeanalytics.common.idempotency.IdempotencyService;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.Coverage;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.IdentityType;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.Subject;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.Target;
import com.storeanalytics.product.model.ProductSourceKind;
import com.storeanalytics.product.repository.CatalogCompatibilityRepository;
import com.storeanalytics.product.service.CatalogCompatibilityRecords.Action;
import com.storeanalytics.product.service.CatalogCompatibilityRecords.Decision;
import com.storeanalytics.product.service.CatalogCompatibilityRecords.Observation;
import com.storeanalytics.product.service.CatalogCompatibilityRecords.Origin;
import com.storeanalytics.product.service.CatalogCompatibilityRecords.Request;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.json.JsonMapper;

/** Real idempotency hashing/codec with in-memory mocked receipts; DB concurrency is tested separately. */
class CatalogCompatibilityServiceTest {
    private final CatalogCompatibilityRepository repository = mock(CatalogCompatibilityRepository.class);
    private final CatalogCompatibilityAuthorizer authorizer = mock(CatalogCompatibilityAuthorizer.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final IdempotencyReceiptRepository receipts = mock(IdempotencyReceiptRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final UUID actor = UUID.randomUUID();
    private final UUID product = UUID.randomUUID();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-30T00:00:00Z"), ZoneOffset.UTC);
    private final Observation observation = new Observation(product, UUID.randomUUID(),
            new Subject("synthetic", ProductSourceKind.PRODUCT, IdentityType.EXTERNAL_ID, "product-1"),
            "123", "Synthetic charger", null);
    private final IdempotencyService idempotency = new IdempotencyService(receipts,
            new IdempotencyProperties(Duration.ofDays(1), 10), jdbc,
            JsonMapper.builder().findAndAddModules().build(), clock);
    private final CatalogCompatibilityService service = service(true);

    @BeforeEach
    void setup() {
        when(authorizer.requireAdministrator()).thenReturn(actor);
        when(repository.observe(eq(product), anyBoolean())).thenReturn(observation);
        when(repository.latest(product)).thenReturn(Optional.empty());
        AtomicReference<IdempotencyReceipt> receipt = new AtomicReference<>();
        when(receipts.findByActorIdAndIdempotencyKey(actor, "catalog-command-1"))
                .thenAnswer(invocation -> Optional.ofNullable(receipt.get()));
        when(receipts.saveAndFlush(any())).thenAnswer(invocation -> {
            receipt.set(invocation.getArgument(0));
            return receipt.get();
        });
    }

    @Test
    void persistsServerActorAndReplaysSameResponseWithoutSecondAppendOrAudit() {
        Request request = confirm();
        String etag = service.preview(product).etag();
        var saved = new Decision(UUID.randomUUID(), product, observation.subject(), observation.fingerprint(), 1,
                Action.CONFIRM, Coverage.EXCLUSIVE, Set.of(Target.APPLE_WATCH), actor, request.reason(),
                Origin.DIRECT_REVIEW, null, null, clock.instant(), null);
        when(repository.append(observation, 1, request, actor)).thenAnswer(invocation -> {
            when(repository.latest(product)).thenReturn(Optional.of(saved));
            return saved;
        });
        var first = service.decide(product, request, etag, "catalog-command-1");
        var retry = service.decide(product, request, etag, "catalog-command-1");
        assertThat(retry).isEqualTo(first);
        assertThat(first.latest()).isEqualTo(saved);
        assertThat(first.etag()).isNotEqualTo(etag);
        verify(repository, times(1)).append(observation, 1, request, actor);
        verify(audit, times(1)).record(eq(actor), eq(null), eq(AuditAction.CATALOG_COMPATIBILITY_DECIDED),
                any(), eq(request.reason()), any(), any());
        assertThatThrownBy(() -> service.decide(product, request, first.etag(), "catalog-command-1"))
                .isInstanceOf(IdempotencyKeyConflictException.class);
        doThrow(new AccessDeniedException("revoked")).when(authorizer).requireAdministrator();
        assertThatThrownBy(() -> service.decide(product, request, etag, "catalog-command-1"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void disabledCommandCannotReachPersistence() {
        assertThatThrownBy(() -> service(false).decide(product, confirm(), "etag", "catalog-command-1"))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(repository, receipts, jdbc, audit);
    }

    @Test
    void missingOrStalePreviewCannotAppendOrAudit() {
        assertThatThrownBy(() -> service.decide(product, confirm(), null, "catalog-command-1"))
                .isInstanceOf(PreconditionRequiredException.class);
        verifyNoInteractions(repository, receipts, jdbc, audit);
        assertThatThrownBy(() -> service.decide(product, confirm(), "stale", "catalog-command-1"))
                .isInstanceOf(PreconditionFailedException.class);
        verifyNoInteractions(audit);
    }

    @Test
    void validatesExactLegacyEvidenceAndRejectsInventedExclusiveCoverage() {
        assertThatThrownBy(() -> new Request(Action.CONFIRM, Coverage.EXCLUSIVE, List.of(Target.APPLE_WATCH),
                "adopt", Origin.LEGACY_ADOPTION, "a".repeat(64), "PRODUCT:123"))
                .isInstanceOf(IllegalArgumentException.class);
        Request wrongKey = new Request(Action.CONFIRM, Coverage.UNDETERMINED, List.of(Target.APPLE_WATCH),
                "adopt", Origin.LEGACY_ADOPTION, "a".repeat(64), "PRODUCT:456");
        assertThatThrownBy(() -> wrongKey.validateFor(observation, actor, clock.instant()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Request(Action.REVOKE, Coverage.UNDETERMINED, List.of(Target.APPLE_WATCH),
                "revoke", Origin.DIRECT_REVIEW, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void targetOrderIsCanonicalAndDuplicatesCannotProduceDifferentFingerprints() {
        var forward = new Request(Action.CONFIRM, Coverage.MULTI_DEVICE, List.of(Target.IPHONE, Target.APPLE_WATCH),
                "devices", Origin.DIRECT_REVIEW, null, null);
        var reverse = new Request(Action.CONFIRM, Coverage.MULTI_DEVICE, List.of(Target.APPLE_WATCH, Target.IPHONE),
                "devices", Origin.DIRECT_REVIEW, null, null);
        assertThat(forward).isEqualTo(reverse);
        assertThatThrownBy(() -> new Request(Action.CONFIRM, Coverage.MULTI_DEVICE,
                List.of(Target.IPHONE, Target.IPHONE), "duplicate", Origin.DIRECT_REVIEW, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void legacyCannotBypassArtifactVerificationThroughOrdinaryDecisionCommand() {
        Request legacy = new Request(Action.CONFIRM, Coverage.UNDETERMINED, List.of(Target.APPLE_WATCH),
                "adopt", Origin.LEGACY_ADOPTION, "a".repeat(64), "PRODUCT:123");
        assertThatThrownBy(() -> service.decide(product, legacy, "etag", "catalog-command-1"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository, receipts, jdbc, audit);
    }

    @Test
    void verifiedLegacyAdoptionUsesNormalRevisionAuditAndIdempotentReplay() throws Exception {
        byte[] artifact = """
                {"format_version":1,"mode":"OWNER_CONFIRMED_NOT_APPLIED","connection_key":"synthetic",
                 "decisions":[{"source_kind":"PRODUCT","code":"123","expected_name":"Synthetic charger",
                 "expected_group":null,"confirmed_compatibility":["APPLE_WATCH"]}]}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(artifact));
        var request = CatalogLegacyCompatibilityEvidence.verify(artifact, hash, "PRODUCT:123").request();
        String etag = service.preview(product).etag();
        var saved = new Decision(UUID.randomUUID(), product, observation.subject(), observation.fingerprint(), 1,
                Action.CONFIRM, Coverage.UNDETERMINED, Set.of(Target.APPLE_WATCH), actor, request.reason(),
                Origin.LEGACY_ADOPTION, hash, "PRODUCT:123", clock.instant(), null);
        when(repository.append(observation, 1, request, actor)).thenAnswer(invocation -> {
            when(repository.latest(product)).thenReturn(Optional.of(saved));
            return saved;
        });
        var first = service.adoptLegacy(product, artifact, hash, "PRODUCT:123", etag, "catalog-command-1");
        var replay = service.adoptLegacy(product, artifact, hash, "PRODUCT:123", etag, "catalog-command-1");
        assertThat(replay).isEqualTo(first);
        assertThat(first.latest()).isEqualTo(saved);
        verify(repository, times(1)).append(observation, 1, request, actor);
        verify(audit, times(1)).record(eq(actor), eq(null), eq(AuditAction.CATALOG_COMPATIBILITY_DECIDED),
                any(), eq(request.reason()), any(), any());
    }

    private Request confirm() {
        return new Request(Action.CONFIRM, Coverage.EXCLUSIVE, List.of(Target.APPLE_WATCH),
                "Synthetic review", Origin.DIRECT_REVIEW, null, null);
    }

    private CatalogCompatibilityService service(boolean enabled) {
        return new CatalogCompatibilityService(repository, authorizer, idempotency, audit, clock, enabled);
    }
}
