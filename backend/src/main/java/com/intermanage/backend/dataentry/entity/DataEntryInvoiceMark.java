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

/** Znacznik faktury w oknie wczytywania (opis w V55__data_entry_invoices.sql); usunięcie to {@code deletedAt}. */
@Entity
@Table(name = "data_entry_invoice_marks")
public class DataEntryInvoiceMark {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private InvoiceMarkKind kind;

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

    protected DataEntryInvoiceMark() {
    }

    public DataEntryInvoiceMark(UUID storeId, UUID invoiceId, InvoiceMarkKind kind, AppUser by) {
        this.storeId = storeId;
        this.invoiceId = invoiceId;
        this.kind = kind;
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

    public UUID getStoreId() {
        return storeId;
    }

    public UUID getInvoiceId() {
        return invoiceId;
    }

    public InvoiceMarkKind getKind() {
        return kind;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
