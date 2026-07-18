package com.storeanalytics.auth.model;

import static com.storeanalytics.common.validation.ModelValidation.requirePersistedId;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class UserStoreAccessId implements Serializable {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    protected UserStoreAccessId() {
    }

    public UserStoreAccessId(UUID userId, UUID storeId) {
        this.userId = requirePersistedId(userId, "userId");
        this.storeId = requirePersistedId(storeId, "storeId");
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getStoreId() {
        return storeId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof UserStoreAccessId that)) {
            return false;
        }
        return Objects.equals(userId, that.userId) && Objects.equals(storeId, that.storeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, storeId);
    }
}
