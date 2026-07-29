package com.storeanalytics.common.observability;

import com.storeanalytics.common.config.ApplicationReleaseProperties;
import com.storeanalytics.common.config.ApplicationRuntimeProperties;
import com.storeanalytics.common.database.ExpectedSchemaVersion;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

@Component
public final class ReleaseIdentityMetrics implements MeterBinder {

    static final String RELEASE_METRIC = "storeanalytics.release.info";

    private final ObjectProvider<BuildProperties> buildPropertiesProvider;
    private final ApplicationRuntimeProperties runtimeProperties;
    private final ApplicationReleaseProperties releaseProperties;
    private final ExpectedSchemaVersion expectedSchemaVersion;

    public ReleaseIdentityMetrics(
            ObjectProvider<BuildProperties> buildPropertiesProvider,
            ApplicationRuntimeProperties runtimeProperties,
            ApplicationReleaseProperties releaseProperties,
            ExpectedSchemaVersion expectedSchemaVersion
    ) {
        this.buildPropertiesProvider = buildPropertiesProvider;
        this.runtimeProperties = runtimeProperties;
        this.releaseProperties = releaseProperties;
        this.expectedSchemaVersion = expectedSchemaVersion;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        BuildProperties build = buildPropertiesProvider.getIfAvailable();
        Gauge.builder(RELEASE_METRIC, () -> 1)
                .description("Running release identity")
                .tag("version", build == null
                        ? "development"
                        : build.getVersion())
                .tag("role", runtimeProperties.role().name())
                .tag("schema_version", expectedSchemaVersion.value())
                .tag("release_id", releaseProperties.id().isEmpty()
                        ? "unassigned"
                        : releaseProperties.id())
                .register(registry);
    }
}
