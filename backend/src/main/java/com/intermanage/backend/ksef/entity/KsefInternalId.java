package com.intermanage.backend.ksef.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;
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
 * Identyfikator wewnętrzny (IDWew: NIP firmy i 5 cyfr), który sklep uznaje za swój: faktury z nim w Podmiocie 3 trafiają
 * do listy tego sklepu. Usunięcie to znacznik.
 */
@Entity
@Table(name = "ksef_internal_ids")
public class KsefInternalId {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(name = "internal_id", nullable = false, length = 16, updatable = false)
    private String internalId;

    @Column(length = 150)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "removed_by")
    private AppUser removedBy;

    @Column(name = "removed_at")
    private Instant removedAt;

    protected KsefInternalId() {
    }

    public KsefInternalId(Store store, String internalId, String name, AppUser createdBy) {
        this.store = store;
        this.internalId = internalId;
        this.name = name;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public void markRemoved(AppUser by) {
        removedAt = Instant.now();
        removedBy = by;
    }

    public void restore() {
        removedAt = null;
        removedBy = null;
    }

    public UUID getId() {
        return id;
    }

    public Store getStore() {
        return store;
    }

    public String getInternalId() {
        return internalId;
    }

    public String getName() {
        return name;
    }

    public AppUser getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getRemovedAt() {
        return removedAt;
    }
}
