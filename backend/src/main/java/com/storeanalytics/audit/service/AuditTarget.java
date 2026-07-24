package com.storeanalytics.audit.service;

public record AuditTarget(String entityType, Object entityId) {
}
