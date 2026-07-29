package com.storeanalytics.common.config;

import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.release")
public record ApplicationReleaseProperties(
        String id,
        String imageDigest
) {

    private static final Pattern RELEASE_ID = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._-]{0,127}"
    );
    private static final Pattern IMAGE_DIGEST = Pattern.compile(
            "sha256:[a-f0-9]{64}"
    );

    public ApplicationReleaseProperties {
        id = normalize(id);
        imageDigest = normalize(imageDigest);
        if (!id.isEmpty() && !RELEASE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Release ID has an invalid format");
        }
        if (!imageDigest.isEmpty()
                && !IMAGE_DIGEST.matcher(imageDigest).matches()) {
            throw new IllegalArgumentException(
                    "Image digest must use the sha256:<64 hex characters> format"
            );
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
