package com.intermanage.backend.collector.entity;

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
 * Kolektor sklepu (program w {@code collector/}). Najpierw ma jednorazowy kod parowania, po parowaniu klucz; w bazie
 * są tylko skróty SHA-256. Kontakt i wersję danych odświeża zapytanie, więc tu są tylko do odczytu.
 */
@Entity
@Table(name = "store_collectors")
public class StoreCollector {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(nullable = false, length = 100, updatable = false)
    private String name;

    @Column(name = "key_hash", length = 64)
    private String keyHash;

    @Column(name = "pair_code_hash", length = 64)
    private String pairCodeHash;

    @Column(name = "pair_expires_at")
    private Instant pairExpiresAt;

    @Column(name = "paired_at")
    private Instant pairedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", insertable = false, updatable = false)
    private Instant lastSeenAt;

    @Column(name = "last_version", insertable = false, updatable = false)
    private String lastVersion;

    @Column(name = "device_name", length = 100)
    private String deviceName;

    @Column(name = "catalog_version", insertable = false, updatable = false)
    private String catalogVersion;

    @Column(name = "catalog_synced_at", insertable = false, updatable = false)
    private Instant catalogSyncedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by")
    private AppUser deletedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected StoreCollector() {
    }

    public StoreCollector(Store store, String name, AppUser createdBy) {
        this.store = store;
        this.name = name;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    /** Nowy kod parowania; dotychczasowy klucz działa, dopóki kolektor nie sparuje się nowym kodem. */
    public void offerPairing(String codeHash, Instant expiresAt) {
        pairCodeHash = codeHash;
        pairExpiresAt = expiresAt;
    }

    public void pair(String newKeyHash, String device) {
        keyHash = newKeyHash;
        deviceName = device;
        pairedAt = Instant.now();
        pairCodeHash = null;
        pairExpiresAt = null;
    }

    public void markDeleted(AppUser by) {
        deletedAt = Instant.now();
        deletedBy = by;
        pairCodeHash = null;
        pairExpiresAt = null;
    }

    public void restore() {
        deletedAt = null;
        deletedBy = null;
    }

    public UUID getId() {
        return id;
    }

    public Store getStore() {
        return store;
    }

    public String getName() {
        return name;
    }

    public Instant getPairExpiresAt() {
        return pairExpiresAt;
    }

    public Instant getPairedAt() {
        return pairedAt;
    }

    public AppUser getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public String getLastVersion() {
        return lastVersion;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public String getCatalogVersion() {
        return catalogVersion;
    }

    public Instant getCatalogSyncedAt() {
        return catalogSyncedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
