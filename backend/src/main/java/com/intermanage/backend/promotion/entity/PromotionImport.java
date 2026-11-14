package com.intermanage.backend.promotion.entity;

import java.time.Instant;
import java.time.LocalDate;
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

/** Jedno wgranie raportu "LISTA BIEŻĄCYCH I PRZYSZŁYCH PROMOCJI" (Taryfy.pdf). */
@Entity
@Table(name = "promotion_imports")
public class PromotionImport {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(name = "file_name", nullable = false, length = 255, updatable = false)
    private String fileName;

    @Column(name = "report_generated_at", updatable = false)
    private LocalDateTime reportGeneratedAt;

    @Column(name = "end_from", updatable = false)
    private LocalDate endFrom;

    @Column(name = "end_to", updatable = false)
    private LocalDate endTo;

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

    @Column(name = "missing_count", nullable = false)
    private int missingCount;

    /** Wgranie wycofane (np. lista innego sklepu, V139): jego promocje są w {@code withdrawn_promotions}. */
    @Column(name = "withdrawn_at", insertable = false, updatable = false)
    private Instant withdrawnAt;

    protected PromotionImport() {
    }

    public PromotionImport(Store store, String fileName, LocalDateTime reportGeneratedAt, LocalDate endFrom,
            LocalDate endTo, AppUser importedBy, int totalRows) {
        this.store = store;
        this.fileName = fileName;
        this.reportGeneratedAt = reportGeneratedAt;
        this.endFrom = endFrom;
        this.endTo = endTo;
        this.importedBy = importedBy;
        this.totalRows = totalRows;
    }

    @PrePersist
    void onCreate() {
        importedAt = Instant.now();
    }

    public void recordResult(int created, int updated, int missing) {
        this.createdCount = created;
        this.updatedCount = updated;
        this.missingCount = missing;
    }

    public UUID getId() {
        return id;
    }

    public String getFileName() {
        return fileName;
    }

    public LocalDateTime getReportGeneratedAt() {
        return reportGeneratedAt;
    }

    public LocalDate getEndFrom() {
        return endFrom;
    }

    public LocalDate getEndTo() {
        return endTo;
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

    public int getMissingCount() {
        return missingCount;
    }
}
