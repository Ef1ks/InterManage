package com.intermanage.backend.meat.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.supplier.entity.Supplier;

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

/** Zapamiętany znak dostawcy z dokumentu WZ (opis w V63__meat_deliveries.sql). Zastąpienie to znacznik. */
@Entity
@Table(name = "meat_supplier_marks")
public class MeatSupplierMark {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private MeatSupplierMarkKind kind;

    @Column(nullable = false, updatable = false, length = 50)
    private String value;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false, updatable = false)
    private Supplier supplier;

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

    protected MeatSupplierMark() {
    }

    public MeatSupplierMark(UUID storeId, MeatSupplierMarkKind kind, String value, Supplier supplier, AppUser by) {
        this.storeId = storeId;
        this.kind = kind;
        this.value = value;
        this.supplier = supplier;
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

    public MeatSupplierMarkKind getKind() {
        return kind;
    }

    public String getValue() {
        return value;
    }

    public Supplier getSupplier() {
        return supplier;
    }
}
