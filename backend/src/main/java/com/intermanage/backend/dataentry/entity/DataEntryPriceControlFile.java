package com.intermanage.backend.dataentry.entity;

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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Plik CTRLPRIX (zmiany cen sprzedaży z tabeli) zapisany przy pliku XML faktury (opis w V65). Listy czytają opis bez
 * treści ({@code DataEntryPriceControlFileRepository.Summary}).
 */
@Entity
@Table(name = "data_entry_price_control_files")
public class DataEntryPriceControlFile {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(name = "supplier_id", updatable = false)
    private UUID supplierId;

    @Column(name = "supplier_code", length = 10, updatable = false)
    private String supplierCode;

    @Column(name = "supplier_keyword", nullable = false, length = 30, updatable = false)
    private String supplierKeyword;

    @Column(name = "supplier_name", updatable = false)
    private String supplierName;

    @Column(name = "invoice_number", updatable = false)
    private String invoiceNumber;

    @Column(name = "file_name", nullable = false, updatable = false)
    private String fileName;

    @Column(name = "line_count", nullable = false, updatable = false)
    private int lineCount;

    @Column(nullable = false, updatable = false)
    private byte[] content;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Program sklepu wczytał plik (końcówka .TraiteN w folderze) albo ktoś oznaczył go ręcznie. */
    @Column(name = "loaded_at")
    private Instant loadedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "loaded_by")
    private AppUser loadedBy;

    @Column(name = "loaded_manually", nullable = false)
    private boolean loadedManually;

    /** N z końcówki .TraiteN: ile razy program sklepu przetworzył plik; puste przy ręcznym oznaczeniu. */
    @Column(name = "processed_count")
    private Integer processedCount;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by")
    private AppUser deletedBy;

    protected DataEntryPriceControlFile() {
    }

    public DataEntryPriceControlFile(UUID storeId, UUID supplierId, String supplierCode, String supplierKeyword,
            String supplierName, String invoiceNumber, String fileName, int lineCount, byte[] content,
            AppUser createdBy) {
        this.storeId = storeId;
        this.supplierId = supplierId;
        this.supplierCode = supplierCode;
        this.supplierKeyword = supplierKeyword;
        this.supplierName = supplierName;
        this.invoiceNumber = invoiceNumber;
        this.fileName = fileName;
        this.lineCount = lineCount;
        this.content = content;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    /**
     * Plik widziany w folderze z końcówką .TraiteN. Niewgrany staje się wgrany; wgrany dostaje tylko wyższą liczbę
     * przetworzeń (program wczytał go ponownie).
     */
    public boolean seenProcessed(int count, AppUser by) {
        if (loadedAt == null) {
            loadedAt = Instant.now();
            loadedBy = by;
            loadedManually = false;
            processedCount = count;
            return true;
        }
        if (processedCount != null && count > processedCount) {
            processedCount = count;
            return true;
        }
        return false;
    }

    public void markLoaded(AppUser by) {
        loadedAt = Instant.now();
        loadedBy = by;
        loadedManually = true;
        processedCount = null;
    }

    public void unmarkLoaded() {
        loadedAt = null;
        loadedBy = null;
        loadedManually = false;
        processedCount = null;
    }

    public void delete(AppUser by) {
        deletedAt = Instant.now();
        deletedBy = by;
    }

    public void restore() {
        deletedAt = null;
        deletedBy = null;
    }

    public boolean isLoaded() {
        return loadedAt != null;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public String getSupplierCode() {
        return supplierCode;
    }

    public String getFileName() {
        return fileName;
    }

    public int getLineCount() {
        return lineCount;
    }

    public byte[] getContent() {
        return content;
    }
}
