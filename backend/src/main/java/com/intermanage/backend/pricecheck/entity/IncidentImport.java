package com.intermanage.backend.pricecheck.entity;

import java.time.Instant;
import java.time.LocalDate;
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

/** Jedno wgranie pliku incydentów zamówień do bazy z jednego dnia (data z nazwy pliku). */
@Entity
@Table(name = "incident_imports")
public class IncidentImport {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(name = "file_name", nullable = false, length = 255, updatable = false)
    private String fileName;

    @Column(name = "incident_date", nullable = false, updatable = false)
    private LocalDate incidentDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "imported_by", updatable = false)
    private AppUser importedBy;

    @Column(name = "imported_at", nullable = false, updatable = false)
    private Instant importedAt;

    @Column(name = "total_rows", nullable = false)
    private int totalRows;

    @Column(name = "added_count", nullable = false)
    private int addedCount;

    protected IncidentImport() {
    }

    public IncidentImport(Store store, String fileName, LocalDate incidentDate, AppUser importedBy, int totalRows) {
        this.store = store;
        this.fileName = fileName;
        this.incidentDate = incidentDate;
        this.importedBy = importedBy;
        this.totalRows = totalRows;
    }

    @PrePersist
    void onCreate() {
        importedAt = Instant.now();
    }

    public void recordResult(int added) {
        this.addedCount = added;
    }

    public UUID getId() {
        return id;
    }

    public String getFileName() {
        return fileName;
    }

    public LocalDate getIncidentDate() {
        return incidentDate;
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

    public int getAddedCount() {
        return addedCount;
    }
}
