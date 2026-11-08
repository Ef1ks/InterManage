package com.intermanage.backend.dataentry.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** Faktura w kolejce do zamknięcia w programie sklepu (opis w V89). */
@Entity
@Table(name = "data_entry_invoice_closings")
public class DataEntryInvoiceClosing {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(name = "export_id", nullable = false)
    private UUID exportId;

    @Column(name = "supplier_code", nullable = false, updatable = false)
    private String supplierCode;

    @Column(name = "document_number", nullable = false, updatable = false)
    private String documentNumber;

    @Column(name = "supplier_name")
    private String supplierName;

    @Column(name = "document_date", nullable = false)
    private LocalDate documentDate;

    @Column(name = "net_value", nullable = false, precision = 14, scale = 2)
    private BigDecimal netValue;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "generated_by")
    private AppUser generatedBy;

    @Column(name = "generated_at")
    private Instant generatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by")
    private AppUser deletedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected DataEntryInvoiceClosing() {
    }

    public DataEntryInvoiceClosing(UUID storeId, UUID exportId, String supplierCode, String documentNumber,
            String supplierName, LocalDate documentDate, BigDecimal netValue, AppUser createdBy) {
        this.storeId = storeId;
        this.exportId = exportId;
        this.supplierCode = supplierCode;
        this.documentNumber = documentNumber;
        this.supplierName = supplierName;
        this.documentDate = documentDate;
        this.netValue = netValue;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    /** Ta sama faktura zapisana ponownie, zanim poszła do skryptu: lista pokazuje dane z ostatniego pliku. */
    public void refresh(UUID exportId, String supplierName, LocalDate documentDate, BigDecimal netValue) {
        this.exportId = exportId;
        this.supplierName = supplierName;
        this.documentDate = documentDate;
        this.netValue = netValue;
    }

    /** Ponowne wygenerowanie skryptu zostawia czas pierwszego. */
    public void markGenerated(AppUser by) {
        if (generatedAt == null) {
            generatedAt = Instant.now();
            generatedBy = by;
        }
    }

    public void delete(AppUser by) {
        deletedAt = Instant.now();
        deletedBy = by;
    }

    public void restore() {
        deletedAt = null;
        deletedBy = null;
    }

    public UUID getId() {
        return id;
    }

    public String getSupplierCode() {
        return supplierCode;
    }

    public String getDocumentNumber() {
        return documentNumber;
    }

    public String getSupplierName() {
        return supplierName;
    }

    public LocalDate getDocumentDate() {
        return documentDate;
    }

    public BigDecimal getNetValue() {
        return netValue;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
