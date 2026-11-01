package com.intermanage.backend.dataentry.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;

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

/**
 * Dostawca z regułą okna wczytywania: pomijany albo odkładany do wczytanych (opis w V55__data_entry_invoices.sql);
 * usunięcie z listy to {@code deletedAt}, przywrócenie je zdejmuje.
 */
@Entity
@Table(name = "data_entry_skipped_suppliers")
public class DataEntrySkippedSupplier {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(name = "seller_nip", nullable = false, updatable = false)
    private String sellerNip;

    @Column(name = "seller_name", updatable = false)
    private String sellerName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private SupplierRuleMode mode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by")
    private AppUser deletedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected DataEntrySkippedSupplier() {
    }

    public DataEntrySkippedSupplier(UUID storeId, String sellerNip, String sellerName, SupplierRuleMode mode,
            AppUser by) {
        this.storeId = storeId;
        this.sellerNip = sellerNip;
        this.sellerName = sellerName;
        this.mode = mode;
        this.createdBy = by;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public void delete(AppUser by) {
        deletedAt = Instant.now();
        deletedBy = by;
    }

    public void restore() {
        deletedAt = null;
        deletedBy = null;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public String getSellerNip() {
        return sellerNip;
    }

    public String getSellerName() {
        return sellerName;
    }

    public SupplierRuleMode getMode() {
        return mode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
