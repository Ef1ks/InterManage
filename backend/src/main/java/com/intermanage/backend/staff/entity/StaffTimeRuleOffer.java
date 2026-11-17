package com.intermanage.backend.staff.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.store.entity.Store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** Zasady odbić zaproponowane sklepowi zaprzyjaźnionemu: przyjęcie, odrzucenie i cofnięcie to znaczniki. */
@Entity
@Table(name = "staff_time_rule_offers")
public class StaffTimeRuleOffer {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_store_id", nullable = false, updatable = false)
    private Store fromStore;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_store_id", nullable = false, updatable = false)
    private Store toStore;

    @Column(name = "rounding_minutes", nullable = false, updatable = false)
    private int roundingMinutes;

    @Column(name = "punch_cooldown_seconds", nullable = false, updatable = false)
    private int punchCooldownSeconds;

    @Enumerated(EnumType.STRING)
    @Column(name = "final_approver", nullable = false, updatable = false, length = 20)
    private TimeRequestApprover finalApprover;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accepted_by")
    private AppUser acceptedBy;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rejected_by")
    private AppUser rejectedBy;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by")
    private AppUser cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    protected StaffTimeRuleOffer() {
    }

    public StaffTimeRuleOffer(Store fromStore, Store toStore, StaffTimeStoreRules.Values values, AppUser createdBy) {
        this.fromStore = fromStore;
        this.toStore = toStore;
        this.roundingMinutes = values.roundingMinutes();
        this.punchCooldownSeconds = values.punchCooldownSeconds();
        this.finalApprover = values.finalApprover();
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public boolean isPending() {
        return acceptedAt == null && rejectedAt == null && cancelledAt == null;
    }

    public void accept(AppUser by) {
        acceptedAt = Instant.now();
        acceptedBy = by;
    }

    public void reject(AppUser by) {
        rejectedAt = Instant.now();
        rejectedBy = by;
    }

    public void cancel(AppUser by) {
        cancelledAt = Instant.now();
        cancelledBy = by;
    }

    public StaffTimeStoreRules.Values values() {
        return new StaffTimeStoreRules.Values(roundingMinutes, punchCooldownSeconds, finalApprover);
    }

    public UUID getId() {
        return id;
    }

    public Store getFromStore() {
        return fromStore;
    }

    public Store getToStore() {
        return toStore;
    }

    public AppUser getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
