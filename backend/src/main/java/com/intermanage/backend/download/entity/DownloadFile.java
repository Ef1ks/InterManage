package com.intermanage.backend.download.entity;

import java.time.Instant;
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

/**
 * Wersja pliku pozycji „Do pobrania”. Nowa wersja daje poprzedniej znacznik zastąpienia. Listy czytają tylko opis
 * ({@code DownloadFileRepository.Summary}), bez treści.
 */
@Entity
@Table(name = "download_files")
public class DownloadFile {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "download_id", nullable = false, updatable = false)
    private Download download;

    @Column(name = "file_name", nullable = false, length = 255, updatable = false)
    private String fileName;

    @Column(length = 40, updatable = false)
    private String version;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private int sizeBytes;

    @Column(nullable = false, length = 64, updatable = false)
    private String sha256;

    @Column(nullable = false, updatable = false)
    private byte[] content;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "replaced_by")
    private AppUser replacedBy;

    @Column(name = "replaced_at")
    private Instant replacedAt;

    protected DownloadFile() {
    }

    public DownloadFile(Download download, String fileName, String version, byte[] content, String sha256,
            AppUser createdBy) {
        this.download = download;
        this.fileName = fileName;
        this.version = version;
        this.sizeBytes = content.length;
        this.sha256 = sha256;
        this.content = content;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public void markReplaced(AppUser by) {
        replacedAt = Instant.now();
        replacedBy = by;
    }

    public UUID getId() {
        return id;
    }

    public String getFileName() {
        return fileName;
    }

    public byte[] getContent() {
        return content;
    }
}
