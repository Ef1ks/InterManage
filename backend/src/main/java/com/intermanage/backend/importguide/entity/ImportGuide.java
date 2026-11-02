package com.intermanage.backend.importguide.entity;

import java.time.Instant;
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
 * Instrukcja do raportu z Importu danych (opis w V73__import_guides.sql). Zmiana daje poprzedniemu wpisowi znacznik
 * {@code deletedAt}.
 */
@Entity
@Table(name = "import_guides")
public class ImportGuide {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false)
    private ImportReportKind kind;

    @Column(name = "content", nullable = false, updatable = false)
    private String content;

    @Column(name = "folder_path", updatable = false)
    private String folderPath;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by")
    private AppUser deletedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected ImportGuide() {
    }

    public ImportGuide(ImportReportKind kind, String content, String folderPath, AppUser by) {
        this.kind = kind;
        this.content = content;
        this.folderPath = folderPath;
        this.createdBy = by;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public void delete(AppUser by) {
        deletedAt = Instant.now();
        deletedBy = by;
    }

    public UUID getId() {
        return id;
    }

    public ImportReportKind getKind() {
        return kind;
    }

    public String getContent() {
        return content;
    }

    public String getFolderPath() {
        return folderPath;
    }

    public AppUser getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
