package com.storeanalytics.auth.model;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requirePersistedId;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class UserFeatureAccessId implements Serializable {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "feature", nullable = false, updatable = false)
    private UserFeature feature;

    protected UserFeatureAccessId() {
    }

    public UserFeatureAccessId(UUID userId, UserFeature feature) {
        this.userId = requirePersistedId(userId, "userId");
        this.feature = requireNonNull(feature, "feature");
    }

    public UUID getUserId() {
        return userId;
    }

    public UserFeature getFeature() {
        return feature;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof UserFeatureAccessId that)) {
            return false;
        }
        return Objects.equals(userId, that.userId) && feature == that.feature;
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, feature);
    }
}
