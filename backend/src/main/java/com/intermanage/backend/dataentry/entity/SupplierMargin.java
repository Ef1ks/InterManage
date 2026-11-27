package com.intermanage.backend.dataentry.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.supplier.entity.Supplier;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Stała marża dostawcy: domyślna (bez nomenklatury) albo wyjątek na kodzie nomenklatury (opis
 * w V204__supplier_margins.sql). Zmiana i usunięcie dają wpisowi znacznik {@code deletedAt}.
 */
@Entity
@Table(name = "supplier_margins")
public class SupplierMargin {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false, updatable = false)
    private Supplier supplier;

    @Column(name = "nomenclature_code", length = 6, updatable = false)
    private String nomenclatureCode;

    @Column(name = "margin_percent", nullable = false, precision = 5, scale = 2, updatable = false)
    private BigDecimal marginPercent;

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

    protected SupplierMargin() {
    }

    public SupplierMargin(Supplier supplier, String nomenclatureCode, BigDecimal marginPercent, AppUser by) {
        this.store = supplier.getStore();
        this.supplier = supplier;
        this.nomenclatureCode = nomenclatureCode;
        this.marginPercent = marginPercent;
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

    public UUID getId() {
        return id;
    }

    public Supplier getSupplier() {
        return supplier;
    }

    public String getNomenclatureCode() {
        return nomenclatureCode;
    }

    public BigDecimal getMarginPercent() {
        return marginPercent;
    }

    public AppUser getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
