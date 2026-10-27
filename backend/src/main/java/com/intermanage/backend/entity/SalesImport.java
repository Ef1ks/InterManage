package com.intermanage.backend.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** Jedno udane wgranie raportu sprzedaży (Wejścia/Wyjścia: sprzedaż wg artykułu i dnia) wraz z podsumowaniem. */
@Entity
@Table(name = "sales_imports")
public class SalesImport {

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

    @Column(name = "period_from", nullable = false, updatable = false)
    private LocalDate periodFrom;

    @Column(name = "period_to", nullable = false, updatable = false)
    private LocalDate periodTo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "imported_by", updatable = false)
    private AppUser importedBy;

    @Column(name = "imported_at", nullable = false, updatable = false)
    private Instant importedAt;

    @Column(name = "total_rows", nullable = false)
    private int totalRows;

    @Column(name = "created_count", nullable = false)
    private int createdCount;

    @Column(name = "existing_count", nullable = false)
    private int existingCount;

    @Column(name = "conflict_count", nullable = false)
    private int conflictCount;

    protected SalesImport() {
    }

    public SalesImport(Store store, String fileName, LocalDateTime reportGeneratedAt, LocalDate periodFrom,
            LocalDate periodTo, AppUser importedBy) {
        this.store = store;
        this.fileName = fileName;
        this.reportGeneratedAt = reportGeneratedAt;
        this.periodFrom = periodFrom;
        this.periodTo = periodTo;
        this.importedBy = importedBy;
    }

    @PrePersist
    void onCreate() {
        importedAt = Instant.now();
    }

    /** Plik jest czytany strumieniowo, więc liczbę wierszy znamy dopiero na końcu. */
    public void recordResult(int totalRows, int created, int conflicting) {
        this.totalRows = totalRows;
        this.createdCount = created;
        this.existingCount = totalRows - created;
        this.conflictCount = conflicting;
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

    public LocalDate getPeriodFrom() {
        return periodFrom;
    }

    public LocalDate getPeriodTo() {
        return periodTo;
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

    public int getExistingCount() {
        return existingCount;
    }

    public int getConflictCount() {
        return conflictCount;
    }
}
