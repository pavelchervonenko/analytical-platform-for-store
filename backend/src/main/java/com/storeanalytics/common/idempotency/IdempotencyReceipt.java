package com.storeanalytics.common.idempotency;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import com.storeanalytics.common.persistence.AbstractCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "idempotency_receipts")
public class IdempotencyReceipt extends AbstractCreatedEntity {

    @Column(name = "actor_id", nullable = false, updatable = false)
    private UUID actorId;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 100)
    private String idempotencyKey;

    @Column(nullable = false, updatable = false, length = 64)
    private String action;

    @Column(name = "resource_identity", nullable = false, updatable = false, length = 256)
    private String resourceIdentity;

    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @Column(name = "response_type", nullable = false, updatable = false, length = 200)
    private String responseType;

    @Column(name = "response_body", nullable = false, updatable = false)
    private String responseBody;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    protected IdempotencyReceipt() {
    }

    public IdempotencyReceipt(
            UUID actorId,
            String idempotencyKey,
            IdempotencyReceiptContent content,
            Instant expiresAt
    ) {
        IdempotencyReceiptContent value = requireNonNull(content, "content");
        this.actorId = requireNonNull(actorId, "actorId");
        this.idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        this.action = requireText(value.action(), "action");
        this.resourceIdentity = requireText(value.resourceIdentity(), "resourceIdentity");
        this.requestHash = requireText(value.requestHash(), "requestHash");
        this.responseType = requireText(value.responseType(), "responseType");
        this.responseBody = requireText(value.responseBody(), "responseBody");
        this.expiresAt = requireNonNull(expiresAt, "expiresAt");
    }

    public String getAction() {
        return action;
    }

    public String getResourceIdentity() {
        return resourceIdentity;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getResponseType() {
        return responseType;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(requireNonNull(now, "now"));
    }
}
