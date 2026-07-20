package com.storeanalytics.auth.model;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.store.model.Store;
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
@Table(name = "user_store_access")
public class UserStoreAccess {

    @EmbeddedId
    private UserStoreAccessId id;

    @MapsId("userId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @MapsId("storeId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "granted_by")
    private AppUser grantedBy;

    @Generated(event = EventType.INSERT)
    @Column(name = "granted_at", nullable = false, insertable = false, updatable = false)
    private Instant grantedAt;

    protected UserStoreAccess() {
    }

    public UserStoreAccess(AppUser user, Store store, AppUser grantedBy) {
        this.user = requireNonNull(user, "user");
        this.store = requireNonNull(store, "store");
        this.id = new UserStoreAccessId(user.getId(), store.getId());
        this.grantedBy = grantedBy;
    }

    public UserStoreAccessId getId() {
        return id;
    }

    public AppUser getUser() {
        return user;
    }

    public Store getStore() {
        return store;
    }

    public Instant getGrantedAt() {
        return grantedAt;
    }
}
