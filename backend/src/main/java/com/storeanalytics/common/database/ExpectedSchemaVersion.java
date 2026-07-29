package com.storeanalytics.common.database;

import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

@Component
public final class ExpectedSchemaVersion {

    private static final String MIGRATION_PATTERN =
            "classpath*:db/migration/V*__*.sql";
    private static final Pattern VERSIONED_MIGRATION = Pattern.compile(
            "^V(.+)__.+\\.sql$"
    );

    private final MigrationVersion version;

    public ExpectedSchemaVersion() {
        version = resolveLatestVersion();
    }

    public String value() {
        return version.getVersion();
    }

    public MigrationVersion migrationVersion() {
        return version;
    }

    private MigrationVersion resolveLatestVersion() {
        Resource[] migrations;
        try {
            migrations = new PathMatchingResourcePatternResolver()
                    .getResources(MIGRATION_PATTERN);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Cannot inspect packaged database migrations",
                    exception
            );
        }
        return Arrays.stream(migrations)
                .map(Resource::getFilename)
                .map(this::parseVersion)
                .max(Comparator.naturalOrder())
                .orElseThrow(() -> new IllegalStateException(
                        "No versioned database migrations are packaged"
                ));
    }

    private MigrationVersion parseVersion(String filename) {
        Matcher matcher = VERSIONED_MIGRATION.matcher(filename);
        if (!matcher.matches()) {
            throw new IllegalStateException(
                    "Unsupported migration filename: " + filename
            );
        }
        return MigrationVersion.fromVersion(
                matcher.group(1).replace('_', '.')
        );
    }
}
