package com.exchange.order.entity;

import jakarta.persistence.*;
import java.time.Instant;

/** Append-only audit trail. Never updated, never deleted. */
@Entity
@Table(name = "audit_log", indexes = @Index(name = "idx_audit_entity", columnList = "entityType, entityId"))
public class AuditLog {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)  private String entityType;   // ORDER, TRADE, ...
    @Column(nullable = false, length = 36)  private String entityId;
    @Column(nullable = false, length = 40)  private String action;       // PLACED, CANCEL_REQUESTED, STATUS_CHANGED
    private Long actorUserId;
    @Column(columnDefinition = "TEXT")      private String detail;       // JSON payload
    @Column(nullable = false, updatable = false) private Instant occurredAt = Instant.now();

    protected AuditLog() {}

    public AuditLog(String entityType, String entityId, String action, Long actorUserId, String detail) {
        this.entityType = entityType; this.entityId = entityId;
        this.action = action; this.actorUserId = actorUserId; this.detail = detail;
    }
}
