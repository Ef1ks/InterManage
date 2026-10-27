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

/** Jedno udane wgranie raportu Wejścia/Wyjścia wraz z podsumowaniem. */
@Entity
@Table(name = "document_imports")
public class DocumentImport {

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

    @Column(name = "period_from", updatable = false)
    private LocalDate periodFrom;

    @Column(name = "period_to", updatable = false)
    private LocalDate periodTo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "imported_by", updatable = false)
    private AppUser importedBy;

    @Column(name = "imported_at", nullable = false, updatable = false)
    private Instant importedAt;

    @Column(name = "total_lines", nullable = false)
    private int totalLines;

    /** Linie typów, które zapisujemy (reszta to {@code skippedLines}). */
    @Column(name = "saved_lines", nullable = false)
    private int savedLines;

    /** Dokumenty, których wcześniej nie było. */
    @Column(name = "created_count", nullable = false)
    private int createdCount;

    /** Dokumenty już znane, uzupełnione o zmiany z tego pliku. */
    @Column(name = "updated_count", nullable = false)
    private int updatedCount;

    @Column(name = "skipped_lines", nullable = false)
    private int skippedLines;

    @Column(name = "skipped_types", length = 200)
    private String skippedTypes;

    protected DocumentImport() {
    }

    public DocumentImport(Store store, String fileName, LocalDateTime reportGeneratedAt, LocalDate periodFrom,
            LocalDate periodTo, AppUser importedBy, int totalLines, int savedLines, int skippedLines, String skippedTypes) {
        this.store = store;
        this.fileName = fileName;
        this.reportGeneratedAt = reportGeneratedAt;
        this.periodFrom = periodFrom;
        this.periodTo = periodTo;
        this.importedBy = importedBy;
        this.totalLines = totalLines;
        this.savedLines = savedLines;
        this.skippedLines = skippedLines;
        this.skippedTypes = skippedTypes;
    }

    @PrePersist
    void onCreate() {
        importedAt = Instant.now();
    }

    public void recordResult(int created, int updated) {
        this.createdCount = created;
        this.updatedCount = updated;
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

    public int getTotalLines() {
        return totalLines;
    }

    public int getSavedLines() {
        return savedLines;
    }

    public int getCreatedCount() {
        return createdCount;
    }

    public int getUpdatedCount() {
        return updatedCount;
    }

    public int getSkippedLines() {
        return skippedLines;
    }

    public String getSkippedTypes() {
        return skippedTypes;
    }
}
