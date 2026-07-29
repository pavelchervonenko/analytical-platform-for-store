package com.storeanalytics.common.observability;

import com.storeanalytics.common.config.ApplicationReleaseProperties;
import com.storeanalytics.common.config.ApplicationRuntimeProperties;
import com.storeanalytics.common.database.ExpectedSchemaVersion;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

@Component
public final class ReleaseInfoContributor implements InfoContributor {

    private static final String APPLICATION_NAME = "store-analytics";

    private final ObjectProvider<BuildProperties> buildPropertiesProvider;
    private final ApplicationRuntimeProperties runtimeProperties;
    private final ApplicationReleaseProperties releaseProperties;
    private final ExpectedSchemaVersion expectedSchemaVersion;

    public ReleaseInfoContributor(
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
    public void contribute(Info.Builder builder) {
        BuildProperties build = buildPropertiesProvider.getIfAvailable();
        Map<String, Object> release = new LinkedHashMap<>();
        release.put("application", APPLICATION_NAME);
        release.put("version", build == null
                ? "development"
                : build.getVersion());
        release.put("runtimeRole", runtimeProperties.role().name());
        release.put("schemaVersion", expectedSchemaVersion.value());
        if (build != null) {
            release.put("buildTime", build.getTime());
        }
        if (!releaseProperties.id().isEmpty()) {
            release.put("id", releaseProperties.id());
        }
        if (!releaseProperties.imageDigest().isEmpty()) {
            release.put("imageDigest", releaseProperties.imageDigest());
        }
        builder.withDetail("release", Map.copyOf(release));
    }
}
