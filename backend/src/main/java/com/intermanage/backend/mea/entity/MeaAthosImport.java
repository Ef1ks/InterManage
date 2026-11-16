package com.intermanage.backend.mea.entity;

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
import jakarta.persistence.Table;

/** Odczyt dziennika ATHOS (Histo.Log) z folderu wybranego w przeglądarce: ile wysyłek było w pliku i ile nowych. */
@Entity
@Table(name = "mea_athos_imports")
public class MeaAthosImport {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "file_modified")
    private Instant fileModified;

    @Column(name = "found_count", nullable = false)
    private int foundCount;

    @Column(name = "added_count", nullable = false)
    private int addedCount;

    @Column(name = "last_sent_at")
    private LocalDateTime lastSentAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "imported_by")
    private AppUser importedBy;

    @Column(name = "imported_at", nullable = false)
    private Instant importedAt;

    protected MeaAthosImport() {
    }

    public MeaAthosImport(Store store, String fileName, Instant fileModified, int foundCount, int addedCount,
            LocalDateTime lastSentAt, AppUser importedBy) {
        this.store = store;
        this.fileName = fileName.length() > 255 ? fileName.substring(0, 255) : fileName;
        this.fileModified = fileModified;
        this.foundCount = foundCount;
        this.addedCount = addedCount;
        this.lastSentAt = lastSentAt;
        this.importedBy = importedBy;
        this.importedAt = Instant.now();
    }

    public String getFileName() {
        return fileName;
    }

    public Instant getFileModified() {
        return fileModified;
    }

    public int getFoundCount() {
        return foundCount;
    }

    public int getAddedCount() {
        return addedCount;
    }

    public LocalDateTime getLastSentAt() {
        return lastSentAt;
    }

    public AppUser getImportedBy() {
        return importedBy;
    }

    public Instant getImportedAt() {
        return importedAt;
    }
}
