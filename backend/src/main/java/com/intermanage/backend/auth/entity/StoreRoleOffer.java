package com.intermanage.backend.auth.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.store.entity.Store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Hierarchia przesłana sklepowi zaprzyjaźnionemu. Treść to rangi z chwili wysłania (JSON), przyjęcie zapisuje
 * poprzednią hierarchię odbiorcy, żeby dało się cofnąć. Przyjęcie, odrzucenie, wycofanie i cofnięcie to znaczniki.
 */
@Entity
@Table(name = "store_role_offers")
public class StoreRoleOffer {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_store_id", nullable = false, updatable = false)
    private Store fromStore;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_store_id", nullable = false, updatable = false)
    private Store toStore;

    @Column(nullable = false, updatable = false)
    private String payload;

    @Column(name = "previous_payload")
    private String previousPayload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accepted_by")
    private AppUser acceptedBy;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rejected_by")
    private AppUser rejectedBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by")
    private AppUser cancelledBy;

    @Column(name = "undone_at")
    private Instant undoneAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "undone_by")
    private AppUser undoneBy;

    protected StoreRoleOffer() {
    }

    public StoreRoleOffer(Store fromStore, Store toStore, String payload, AppUser createdBy) {
        this.fromStore = fromStore;
        this.toStore = toStore;
        this.payload = payload;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public boolean isPending() {
        return acceptedAt == null && rejectedAt == null && cancelledAt == null;
    }

    public boolean canUndo() {
        return acceptedAt != null && undoneAt == null && previousPayload != null;
    }

    public void accept(String previous, AppUser by) {
        previousPayload = previous;
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

    public void undo(AppUser by) {
        undoneAt = Instant.now();
        undoneBy = by;
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

    public String getPayload() {
        return payload;
    }

    public String getPreviousPayload() {
        return previousPayload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public AppUser getCreatedBy() {
        return createdBy;
    }
}
