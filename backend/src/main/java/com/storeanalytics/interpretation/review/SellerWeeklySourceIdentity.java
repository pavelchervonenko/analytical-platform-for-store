package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse.VersionSet;
import com.storeanalytics.store.service.StoreDataStatusView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/** Hashes only canonical scope, cohort, coverage, freshness and version identity; no business values. */
@Component
class SellerWeeklySourceIdentity {

    String hash(SellerWeeklyReviewFacts facts) {
        return hash(SellerWeeklyIdentityFacts.from(facts));
    }

    String hash(SellerWeeklyIdentityFacts facts) {
        SellerWeeklyIdentityFacts source = requireNonNull(facts, "facts");
        StoreDataStatusView status = source.sourceDataStatus();
        VersionSet versions = SellerWeeklyV3Assembler.versions();
        String canonical = String.join("\n",
                "seller-weekly-source-v1",
                source.storeId().toString(),
                source.period().timezone(),
                source.period().current().start().toString(),
                source.period().current().end().toString(),
                source.period().previous().start().toString(),
                source.period().previous().end().toString(),
                Long.toString(source.sourceRevision()),
                source.cohortFingerprint(),
                source.currentAttachFormulaVersion(),
                source.previousAttachFormulaVersion(),
                status.status().name(),
                String.valueOf(status.expectedThroughDate()),
                String.valueOf(status.dataThroughDate()),
                Long.toString(status.openQualityIssueCount()),
                source.sourceStability().name(),
                window(source.sourceCoverage().sales()),
                window(source.sourceCoverage().returns()),
                window(source.sourceCoverage().orders()),
                versions.metricsPolicy(),
                versions.snapshotPolicy(),
                versions.qualityPolicy());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String window(SellerWeeklySourceCoverage.Window value) {
        return Boolean.toString(value.current()) + "," + value.previous();
    }
}
