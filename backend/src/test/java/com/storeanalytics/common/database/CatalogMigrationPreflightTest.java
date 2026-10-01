package com.storeanalytics.common.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class CatalogMigrationPreflightTest {
    @Test
    void doesNotAllowDirectHistoricalCatalogWritesInPreparedChain() throws Exception {
        Pattern versionPattern = Pattern.compile("V([0-9]+)__.*\\.sql");
        Pattern writes = Pattern.compile(
                "(?i)\\b(?:UPDATE|INSERT\\s+INTO|DELETE\\s+FROM)\\s+"
                        + "(?:app\\.)?(?:sales_document_items|product_category_assignments"
                        + "|product_payroll_category_assignments)\\b"
        );
        Set<String> found = new HashSet<>();
        var resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/V*.sql");
        for (var resource : resources) {
            var version = versionPattern.matcher(resource.getFilename());
            if (!version.matches() || Integer.parseInt(version.group(1)) <= 51) {
                continue;
            }
            String sql;
            try (var stream = resource.getInputStream()) {
                sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (writes.matcher(sql).find()) {
                found.add(version.group(1));
                assertThat(CatalogMigrationPreflight.isHistoricalRewrite(version.group(1)))
                        .withFailMessage("Review and guard catalog writes in %s", resource.getFilename())
                        .isTrue();
            }
        }
        // This static tripwire complements the populated-database rehearsal; it is not a SQL parser.
        assertThat(found).as("Prospective migrations must not rewrite existing classifications").isEmpty();
        assertThat(CatalogMigrationPreflight.isHistoricalRewrite("88")).isFalse();
    }
}
