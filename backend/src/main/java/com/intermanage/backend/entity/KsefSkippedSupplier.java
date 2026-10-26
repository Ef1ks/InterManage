package com.intermanage.backend.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Stały dostawca (po NIP), którego faktur sklep nie chce widzieć na liście faktur KSeF, np. media czy leasing. Faktury
 * dalej są pobierane - lista pokazuje je po włączeniu "Pokaż pominiętych". Usunięcie to znacznik.
 */
@Entity
@Table(name = "ksef_skipped_suppliers")
public class KsefSkippedSupplier {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(nullable = false, length = 10, updatable = false)
    private String nip;

    @Column(length = 512)
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

    protected KsefSkippedSupplier() {
    }

    public KsefSkippedSupplier(Store store, String nip, String name, AppUser createdBy) {
        this.store = store;
        this.nip = nip;
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

    public String getNip() {
        return nip;
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
