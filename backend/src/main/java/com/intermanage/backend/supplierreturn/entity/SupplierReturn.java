package com.intermanage.backend.supplierreturn.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.document.entity.Document;
import com.intermanage.backend.ksef.entity.KsefInvoice;
import com.intermanage.backend.supplier.entity.Supplier;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Zwrot do dostawcy: artykuły do oddania, stan rozliczenia i dokumenty: bon dostawy, z którą towar przyjechał, bon
 * korekty z raportu dokumentów i korekta z KSeF. Zmiany dokumentów i stanu zapisuje historia
 * ({@link SupplierReturnEvent}); usunięcie to znacznik.
 */
@Entity
@Table(name = "supplier_returns")
public class SupplierReturn {

    public enum Source {
        COLLECTOR, PROTOCOLS
    }

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SupplierReturnStatus status = SupplierReturnStatus.NEW;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Source source;

    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delivery_document_id")
    private Document deliveryDocument;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "correction_document_id")
    private Document correctionDocument;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ksef_invoice_id")
    private KsefInvoice ksefInvoice;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by")
    private AppUser deletedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected SupplierReturn() {
    }

    public SupplierReturn(UUID storeId, Source source, Supplier supplier, String note, AppUser createdBy) {
        this.storeId = storeId;
        this.source = source;
        this.supplier = supplier;
        this.note = note;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void setStatus(SupplierReturnStatus status) {
        this.status = status;
        touch();
    }

    public void setSupplier(Supplier supplier) {
        this.supplier = supplier;
        touch();
    }

    public void setNote(String note) {
        this.note = note;
        touch();
    }

    public void setDeliveryDocument(Document document) {
        this.deliveryDocument = document;
        touch();
    }

    public void setCorrectionDocument(Document document) {
        this.correctionDocument = document;
        touch();
    }

    public void setKsefInvoice(KsefInvoice invoice) {
        this.ksefInvoice = invoice;
        touch();
    }

    public void markDeleted(AppUser by) {
        deletedAt = Instant.now();
        deletedBy = by;
    }

    public void restore() {
        deletedAt = null;
        deletedBy = null;
    }

    private void touch() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public Supplier getSupplier() {
        return supplier;
    }

    public SupplierReturnStatus getStatus() {
        return status;
    }

    public Source getSource() {
        return source;
    }

    public String getNote() {
        return note;
    }

    public Document getDeliveryDocument() {
        return deliveryDocument;
    }

    public Document getCorrectionDocument() {
        return correctionDocument;
    }

    public KsefInvoice getKsefInvoice() {
        return ksefInvoice;
    }

    public AppUser getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
