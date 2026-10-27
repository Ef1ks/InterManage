package com.intermanage.backend.product.entity;

import java.time.Instant;
import java.time.LocalDateTime;
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

/** Jedno udane wgranie raportu kartoteki produktów wraz z podsumowaniem zmian. */
@Entity
@Table(name = "product_imports")
public class ProductImport {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(name = "file_name", nullable = false, length = 255, updatable = false)
    private String fileName;

    /** Data z nagłówka raportu; {@code null}, gdy plik jej nie zawierał. */
    @Column(name = "report_generated_at", updatable = false)
    private LocalDateTime reportGeneratedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "imported_by", updatable = false)
    private AppUser importedBy;

    @Column(name = "imported_at", nullable = false, updatable = false)
    private Instant importedAt;

    @Column(name = "total_rows", nullable = false)
    private int totalRows;

    @Column(name = "created_count", nullable = false)
    private int createdCount;

    @Column(name = "updated_count", nullable = false)
    private int updatedCount;

    @Column(name = "unchanged_count", nullable = false)
    private int unchangedCount;

    @Column(name = "missing_count", nullable = false)
    private int missingCount;

    /** Produkty (ITM8), które dostały inny EAN niż w poprzednim imporcie. */
    @Column(name = "ean_changed_count", nullable = false)
    private int eanChangedCount;

    /** EAN-y, które wcześniej należały do innego ITM8. */
    @Column(name = "ean_moved_count", nullable = false)
    private int eanMovedCount;

    protected ProductImport() {
    }

    public ProductImport(Store store, String fileName, LocalDateTime reportGeneratedAt, AppUser importedBy,
            int totalRows) {
        this.store = store;
        this.fileName = fileName;
        this.reportGeneratedAt = reportGeneratedAt;
        this.importedBy = importedBy;
        this.totalRows = totalRows;
    }

    @PrePersist
    void onCreate() {
        importedAt = Instant.now();
    }

    public void recordResult(int created, int updated, int unchanged, int missing, int eanChanged, int eanMoved) {
        this.createdCount = created;
        this.updatedCount = updated;
        this.unchangedCount = unchanged;
        this.missingCount = missing;
        this.eanChangedCount = eanChanged;
        this.eanMovedCount = eanMoved;
    }

    public UUID getId() {
        return id;
    }

    public Store getStore() {
        return store;
    }

    public String getFileName() {
        return fileName;
    }

    public LocalDateTime getReportGeneratedAt() {
        return reportGeneratedAt;
    }

    public AppUser getImportedBy() {
        return importedBy;
    }

    public Instant getImportedAt() {
        return importedAt;
    }

    public int getTotalRows() {
        return totalRows;
    }

    public int getCreatedCount() {
        return createdCount;
    }

    public int getUpdatedCount() {
        return updatedCount;
    }

    public int getUnchangedCount() {
        return unchangedCount;
    }

    public int getMissingCount() {
        return missingCount;
    }

    public int getEanChangedCount() {
        return eanChangedCount;
    }

    public int getEanMovedCount() {
        return eanMovedCount;
    }
}
