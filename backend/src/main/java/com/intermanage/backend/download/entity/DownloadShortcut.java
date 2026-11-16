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

/** Skrót Windows do folderu na stronie „Do pobrania”; pobiera się jako {@code <nazwa>.lnk}. */
@Entity
@Table(name = "download_shortcuts")
public class DownloadShortcut {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    /** Folder odczytany ze skrótu; puste, gdy się nie dało. */
    @Column(length = 1000)
    private String target;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    @Column(nullable = false)
    private byte[] content;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "removed_by")
    private AppUser removedBy;

    @Column(name = "removed_at")
    private Instant removedAt;

    protected DownloadShortcut() {
    }

    public DownloadShortcut(String name, String target, byte[] content, AppUser createdBy) {
        this.name = name;
        this.target = target;
        this.sizeBytes = content.length;
        this.content = content;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public void rename(String name) {
        this.name = name;
        this.updatedAt = Instant.now();
    }

    public void markRemoved(AppUser by) {
        removedAt = Instant.now();
        removedBy = by;
    }

    public void restore() {
        removedAt = null;
        removedBy = null;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getTarget() {
        return target;
    }

    public byte[] getContent() {
        return content;
    }

    public Instant getRemovedAt() {
        return removedAt;
    }

    /** Nazwa pobranego pliku. */
    public String getFileName() {
        return name + ".lnk";
    }
}
