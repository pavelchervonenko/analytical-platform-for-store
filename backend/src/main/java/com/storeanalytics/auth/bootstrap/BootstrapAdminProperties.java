package com.storeanalytics.auth.bootstrap;

import com.storeanalytics.auth.service.PasswordPolicy;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.security.bootstrap-admin")
public record BootstrapAdminProperties(
        @Email @Size(max = 254) String email,
        @Size(max = PasswordPolicy.MAXIMUM_LENGTH) String password,
        @Size(max = 200) String displayName
) {

    public boolean configured() {
        return StringUtils.hasText(email) || StringUtils.hasText(password);
    }

    public void validateCompleteConfiguration() {
        if (!StringUtils.hasText(email) || !StringUtils.hasText(password)) {
            throw new IllegalStateException(
                    "Both bootstrap admin email and password must be configured"
            );
        }
    }

    public String resolvedDisplayName() {
        return StringUtils.hasText(displayName) ? displayName : "Administrator";
    }

    @Override
    public String toString() {
        return "BootstrapAdminProperties["
                + "emailConfigured=" + StringUtils.hasText(email)
                + ", passwordConfigured=" + StringUtils.hasText(password)
                + ", displayNameConfigured=" + StringUtils.hasText(displayName)
                + "]";
    }
}
