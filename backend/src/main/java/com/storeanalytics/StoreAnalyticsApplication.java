package com.storeanalytics;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.ApplicationRoleResolver;
import com.storeanalytics.common.database.ExpectedSchemaVersion;

import java.io.PrintStream;
import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
@ConfigurationPropertiesScan
public class StoreAnalyticsApplication {

    public static void main(String[] args) {
        if (printExpectedSchemaVersion(args, System.out)) {
            return;
        }
        if (ApplicationRoleResolver.resolve(args) == ApplicationRole.MIGRATION) {
            try (var ignored = MigrationApplication.run(args)) {
                return;
            }
        }
        SpringApplication.run(StoreAnalyticsApplication.class, args);
    }

    static boolean printExpectedSchemaVersion(String[] args, PrintStream output) {
        if (Arrays.asList(args).contains("--print-expected-schema-version")) {
            output.println(new ExpectedSchemaVersion().value());
            return true;
        }
        return false;
    }
}
