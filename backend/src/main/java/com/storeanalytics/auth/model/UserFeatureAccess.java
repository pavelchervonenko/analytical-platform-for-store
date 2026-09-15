package com.storeanalytics.auth.model;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

@Entity
@Table(name = "user_feature_access")
public class UserFeatureAccess {

    @EmbeddedId
    private UserFeatureAccessId id;

    @MapsId("userId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "granted_by")
    private AppUser grantedBy;

    @Generated(event = EventType.INSERT)
    @Column(name = "granted_at", nullable = false, insertable = false, updatable = false)
    private Instant grantedAt;

    protected UserFeatureAccess() {
    }

    public UserFeatureAccess(AppUser user, UserFeature feature, AppUser grantedBy) {
        this.user = requireNonNull(user, "user");
        this.id = new UserFeatureAccessId(user.getId(), feature);
        this.grantedBy = grantedBy;
    }

    public UserFeatureAccessId getId() {
        return id;
    }

    public AppUser getUser() {
        return user;
    }

    public Instant getGrantedAt() {
        return grantedAt;
    }
}
