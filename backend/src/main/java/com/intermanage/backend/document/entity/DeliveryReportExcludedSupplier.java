package com.intermanage.backend.document.entity;

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
 * Dostawca, którego przyjęć PZ nie ma w raporcie przyjęć za okres (po kodzie dostawcy przyjęcia). Usunięcie z listy to
 * znacznik.
 */
@Entity
@Table(name = "delivery_report_excluded_suppliers")
public class DeliveryReportExcludedSupplier {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(name = "supplier_code", nullable = false, length = 10, updatable = false)
    private String supplierCode;

    @Column(name = "supplier_name", length = 200, updatable = false)
    private String supplierName;

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

    protected DeliveryReportExcludedSupplier() {
    }

    public DeliveryReportExcludedSupplier(Store store, String supplierCode, String supplierName, AppUser createdBy) {
        this.store = store;
        this.supplierCode = supplierCode;
        this.supplierName = supplierName;
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

    public UUID getId() {
        return id;
    }

    public String getSupplierCode() {
        return supplierCode;
    }

    public String getSupplierName() {
        return supplierName;
    }

    public Instant getRemovedAt() {
        return removedAt;
    }

    /** Kod i nazwa do opisu w raporcie. */
    public String label() {
        return supplierName != null ? supplierCode + " " + supplierName : supplierCode;
    }
}
