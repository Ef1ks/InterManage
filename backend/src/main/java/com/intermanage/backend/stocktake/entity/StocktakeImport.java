package com.intermanage.backend.stocktake.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
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

/** Jedno wgranie raportu porównania stanów: cały sklep albo wydruk jednego działu. */
@Entity
@Table(name = "stocktake_imports")
public class StocktakeImport {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stocktake_id", nullable = false, updatable = false)
    private Stocktake stocktake;

    @Column(name = "file_name", nullable = false, length = 255, updatable = false)
    private String fileName;

    @Column(name = "selection", length = 200, updatable = false)
    private String selection;

    @Column(name = "whole_store", nullable = false, updatable = false)
    private boolean wholeStore;

    @Column(name = "report_generated_at", nullable = false, updatable = false)
    private LocalDateTime reportGeneratedAt;

    @Column(name = "page_count", nullable = false, updatable = false)
    private int pageCount;

    @Column(name = "line_count", nullable = false, updatable = false)
    private int lineCount;

    @Column(name = "difference_value", nullable = false, precision = 14, scale = 2, updatable = false)
    private BigDecimal differenceValue;

    @Column(name = "created_count", nullable = false)
    private int createdCount;

    @Column(name = "updated_count", nullable = false)
    private int updatedCount;

    @Column(name = "resolved_count", nullable = false)
    private int resolvedCount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "imported_by", updatable = false)
    private AppUser importedBy;

    @Column(name = "imported_at", nullable = false, updatable = false)
    private Instant importedAt;

    protected StocktakeImport() {
    }

    public StocktakeImport(Stocktake stocktake, String fileName, String selection, boolean wholeStore,
            LocalDateTime reportGeneratedAt, int pageCount, int lineCount, BigDecimal differenceValue,
            AppUser importedBy) {
        this.stocktake = stocktake;
        this.fileName = fileName;
        this.selection = selection;
        this.wholeStore = wholeStore;
        this.reportGeneratedAt = reportGeneratedAt;
        this.pageCount = pageCount;
        this.lineCount = lineCount;
        this.differenceValue = differenceValue;
        this.importedBy = importedBy;
    }

    @PrePersist
    void onCreate() {
        importedAt = Instant.now();
    }

    public void recordResult(int created, int updated, int resolved) {
        this.createdCount = created;
        this.updatedCount = updated;
        this.resolvedCount = resolved;
    }

    public UUID getId() {
        return id;
    }

    public Stocktake getStocktake() {
        return stocktake;
    }

    public String getFileName() {
        return fileName;
    }

    public String getSelection() {
        return selection;
    }

    public boolean isWholeStore() {
        return wholeStore;
    }

    public LocalDateTime getReportGeneratedAt() {
        return reportGeneratedAt;
    }

    public int getLineCount() {
        return lineCount;
    }

    public BigDecimal getDifferenceValue() {
        return differenceValue;
    }

    public int getCreatedCount() {
        return createdCount;
    }

    public int getUpdatedCount() {
        return updatedCount;
    }

    public int getResolvedCount() {
        return resolvedCount;
    }

    public AppUser getImportedBy() {
        return importedBy;
    }

    public Instant getImportedAt() {
        return importedAt;
    }
}
