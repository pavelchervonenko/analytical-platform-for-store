package com.storeanalytics.salary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.salary.exception.PayrollSourceDataChangedException;
import com.storeanalytics.salary.model.PayrollRun;
import com.storeanalytics.salary.model.PayrollSourceFingerprint;
import com.storeanalytics.store.model.Store;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PayrollFreshnessServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-01T10:00:00Z");

    @Test
    void reportsStableReasonsAndBlocksStaleTransition() {
        PayrollCalculationSource source = mock(PayrollCalculationSource.class);
        PayrollSourceFingerprintService fingerprintService =
                mock(PayrollSourceFingerprintService.class);
        PayrollRun run = run();
        PayrollCalculationSourceData currentSource = mock(PayrollCalculationSourceData.class);
        PayrollSourceFingerprint stored = fingerprint("a", "b", "c", "d", "e");
        PayrollSourceFingerprint current = fingerprint("f", "b", "c", "9", "e");
        when(run.getSourceFingerprint()).thenReturn(stored);
        when(source.load(run.getStore().getId(), YearMonth.of(2026, 7)))
                .thenReturn(currentSource);
        when(fingerprintService.capture(currentSource)).thenReturn(current);
        PayrollFreshnessService service = new PayrollFreshnessService(
                source, fingerprintService, Clock.fixed(NOW, ZoneOffset.UTC)
        );

        PayrollFreshnessView result = service.evaluate(run);

        assertThat(result.status()).isEqualTo(PayrollFreshnessStatus.STALE);
        assertThat(result.requiresRecalculation()).isTrue();
        assertThat(result.reasons()).containsExactly(
                PayrollStaleReason.SALES_DATA_CHANGED,
                PayrollStaleReason.PRODUCT_CLASSIFICATION_CHANGED
        );
        assertThat(result.checkedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> service.requireCurrent(run))
                .isInstanceOf(PayrollSourceDataChangedException.class)
                .satisfies(exception -> assertThat(
                        ((PayrollSourceDataChangedException) exception).getReasons()
                ).containsExactlyElementsOf(result.reasons()));
    }

    @Test
    void marksLegacyRunUnknownWithoutLoadingCurrentSources() {
        PayrollCalculationSource source = mock(PayrollCalculationSource.class);
        PayrollRun run = run();
        when(run.getSourceFingerprint()).thenReturn(null);
        PayrollFreshnessService service = new PayrollFreshnessService(
                source,
                mock(PayrollSourceFingerprintService.class),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        PayrollFreshnessView result = service.evaluate(run);

        assertThat(result.status()).isEqualTo(PayrollFreshnessStatus.UNKNOWN);
        assertThat(result.reasons())
                .containsExactly(PayrollStaleReason.SOURCE_FINGERPRINT_MISSING);
    }

    private PayrollRun run() {
        Store store = mock(Store.class);
        when(store.getId()).thenReturn(UUID.randomUUID());
        PayrollRun run = mock(PayrollRun.class);
        when(run.getStore()).thenReturn(store);
        when(run.getPeriodMonth()).thenReturn(LocalDate.of(2026, 7, 1));
        return run;
    }

    private PayrollSourceFingerprint fingerprint(
            String sales,
            String shifts,
            String plan,
            String classification,
            String scheme
    ) {
        return new PayrollSourceFingerprint(
                1,
                sales.repeat(64),
                shifts.repeat(64),
                plan.repeat(64),
                classification.repeat(64),
                scheme.repeat(64)
        );
    }
}
