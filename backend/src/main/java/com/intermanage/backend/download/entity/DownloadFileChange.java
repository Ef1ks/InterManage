package com.intermanage.backend.download.entity;

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

/** Wpis listy zmian wersji pliku („Dodano …”, „Naprawiono …”). Poprawka listy daje wpisom znacznik usunięcia. */
@Entity
@Table(name = "download_file_changes")
public class DownloadFileChange {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_id", nullable = false, updatable = false)
    private DownloadFile file;

    @Column(name = "file_id", insertable = false, updatable = false)
    private UUID fileId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private DownloadChangeKind kind;

    @Column(nullable = false, length = 500, updatable = false)
    private String text;

    @Column(nullable = false, updatable = false)
    private int position;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "removed_by")
    private AppUser removedBy;

    @Column(name = "removed_at")
    private Instant removedAt;

    protected DownloadFileChange() {
    }

    public DownloadFileChange(DownloadFile file, DownloadChangeKind kind, String text, int position,
            AppUser createdBy) {
        this.file = file;
        this.kind = kind;
        this.text = text;
        this.position = position;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public void markRemoved(AppUser by) {
        removedAt = Instant.now();
        removedBy = by;
    }

    public UUID getFileId() {
        return fileId;
    }

    public DownloadChangeKind getKind() {
        return kind;
    }

    public String getText() {
        return text;
    }
}
