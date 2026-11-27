package com.intermanage.backend.supplierreturn.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Wpis historii zwrotu (tylko dopisywany); bez osoby = zrobione automatycznie. */
@Entity
@Table(name = "supplier_return_events")
public class SupplierReturnEvent {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "return_id", nullable = false, updatable = false)
    private UUID returnId;

    @Column(nullable = false, updatable = false)
    private String kind;

    @Column(updatable = false)
    private String detail;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SupplierReturnEvent() {
    }

    public SupplierReturnEvent(UUID returnId, String kind, String detail, AppUser createdBy) {
        this.returnId = returnId;
        this.kind = kind;
        this.detail = detail == null || detail.length() <= 500 ? detail : detail.substring(0, 500);
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public UUID getReturnId() {
        return returnId;
    }

    public String getKind() {
        return kind;
    }

    public String getDetail() {
        return detail;
    }

    public AppUser getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
