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

/** Wczytany plik TRACEART (zmiany cen na kasie); nazwa pliku jest unikalna w sklepie, więc plik idzie raz. */
@Entity
@Table(name = "price_file_imports")
public class PriceFileImport {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(name = "file_name", nullable = false, length = 255, updatable = false)
    private String fileName;

    @Column(name = "file_time", nullable = false, updatable = false)
    private LocalDateTime fileTime;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "imported_by", updatable = false)
    private AppUser importedBy;

    @Column(name = "imported_at", nullable = false, updatable = false)
    private Instant importedAt;

    @Column(name = "total_lines", nullable = false)
    private int totalLines;

    @Column(name = "article_count", nullable = false)
    private int articleCount;

    @Column(name = "updated_count", nullable = false)
    private int updatedCount;

    @Column(name = "older_count", nullable = false)
    private int olderCount;

    @Column(name = "unknown_count", nullable = false)
    private int unknownCount;

    protected PriceFileImport() {
    }

    public PriceFileImport(Store store, String fileName, LocalDateTime fileTime, AppUser importedBy, int totalLines) {
        this.store = store;
        this.fileName = fileName;
        this.fileTime = fileTime;
        this.importedBy = importedBy;
        this.totalLines = totalLines;
    }

    @PrePersist
    void onCreate() {
        importedAt = Instant.now();
    }

    public void recordResult(int articles, int updated, int older, int unknown) {
        this.articleCount = articles;
        this.updatedCount = updated;
        this.olderCount = older;
        this.unknownCount = unknown;
    }

    public UUID getId() {
        return id;
    }

    public String getFileName() {
        return fileName;
    }

    public LocalDateTime getFileTime() {
        return fileTime;
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

    public int getArticleCount() {
        return articleCount;
    }

    public int getUpdatedCount() {
        return updatedCount;
    }

    public int getOlderCount() {
        return olderCount;
    }

    public int getUnknownCount() {
        return unknownCount;
    }
}
