package com.intermanage.backend.dataentry.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
 * Plik XML faktury z wprowadzania danych (opis w V57 i V58): zapisany na dysku ({@code SAVED},
 * w historii od {@code savedAt}) albo tymczasowy ({@code TEMPORARY}, z tabelą w {@code draft}). Listy czytają opis bez
 * treści ({@code DataEntryXmlExportRepository.Summary}).
 */
@Entity
@Table(name = "data_entry_xml_exports")
public class DataEntryXmlExport {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private XmlExportKind kind;

    @Column(name = "invoice_id", updatable = false)
    private UUID invoiceId;

    @Column(name = "file_name", nullable = false, updatable = false)
    private String fileName;

    @Column(name = "document_number", updatable = false)
    private String documentNumber;

    @Column(name = "document_date", nullable = false, updatable = false)
    private LocalDate documentDate;

    @Column(name = "supplier_name", updatable = false)
    private String supplierName;

    @Column(name = "supplier_nip", length = 20, updatable = false)
    private String supplierNip;

    @Column(name = "line_count", nullable = false, updatable = false)
    private int lineCount;

    @Column(name = "net_value", nullable = false, precision = 14, scale = 2, updatable = false)
    private BigDecimal netValue;

    @Column(nullable = false, updatable = false)
    private byte[] content;

    @Column(updatable = false)
    private String draft;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "saved_at")
    private Instant savedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by")
    private AppUser deletedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected DataEntryXmlExport() {
    }

    public DataEntryXmlExport(UUID storeId, XmlExportKind kind, UUID invoiceId, String fileName,
            String documentNumber, LocalDate documentDate, String supplierName, String supplierNip, int lineCount,
            BigDecimal netValue, byte[] content, String draft, AppUser createdBy) {
        this.storeId = storeId;
        this.kind = kind;
        this.invoiceId = invoiceId;
        this.fileName = fileName;
        this.documentNumber = documentNumber;
        this.documentDate = documentDate;
        this.supplierName = supplierName;
        this.supplierNip = supplierNip;
        this.lineCount = lineCount;
        this.netValue = netValue;
        this.content = content;
        this.draft = draft;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    /** Przeglądarka zapisała plik na dysku: od teraz jest w historii. */
    public void markSaved() {
        if (savedAt == null) {
            savedAt = Instant.now();
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

    public UUID getStoreId() {
        return storeId;
    }

    public XmlExportKind getKind() {
        return kind;
    }

    public UUID getInvoiceId() {
        return invoiceId;
    }

    public String getFileName() {
        return fileName;
    }

    public byte[] getContent() {
        return content;
    }

    public String getDraft() {
        return draft;
    }

    public Instant getSavedAt() {
        return savedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
