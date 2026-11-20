package com.intermanage.backend.store.entity;

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
 * Plik czekający w kolejce sklepu na zapis w folderze (praca dla innego sklepu bez połączenia z jego komputerem).
 * Zapisany ma {@code savedAt}, usunięty z kolejki znacznik {@code removedAt}.
 */
@Entity
@Table(name = "store_file_queue")
public class StoreFileQueueItem {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(name = "folder_key", nullable = false, updatable = false, length = 100)
    private String folderKey;

    @Column(name = "folder_label", nullable = false, updatable = false, length = 200)
    private String folderLabel;

    /** Dokument, do którego należy plik (np. faktura), albo {@code null}, gdy plik stoi sam. */
    @Column(name = "group_key", updatable = false, length = 200)
    private String groupKey;

    @Column(name = "group_label", updatable = false, length = 300)
    private String groupLabel;

    @Column(name = "file_name", nullable = false, updatable = false)
    private String fileName;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private int sizeBytes;

    @Column(nullable = false, updatable = false)
    private byte[] content;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "saved_by")
    private AppUser savedBy;

    @Column(name = "saved_at")
    private Instant savedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "removed_by")
    private AppUser removedBy;

    @Column(name = "removed_at")
    private Instant removedAt;

    protected StoreFileQueueItem() {
    }

    public StoreFileQueueItem(UUID storeId, String folderKey, String folderLabel, String groupKey, String groupLabel,
            String fileName, byte[] content, AppUser createdBy) {
        this.storeId = storeId;
        this.folderKey = folderKey;
        this.folderLabel = folderLabel;
        this.groupKey = groupKey;
        this.groupLabel = groupLabel;
        this.fileName = fileName;
        this.sizeBytes = content.length;
        this.content = content;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public void markSaved(AppUser by) {
        if (savedAt == null) {
            savedAt = Instant.now();
            savedBy = by;
        }
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

    public UUID getStoreId() {
        return storeId;
    }

    public String getFolderKey() {
        return folderKey;
    }

    public String getFolderLabel() {
        return folderLabel;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getFileName() {
        return fileName;
    }

    public byte[] getContent() {
        return content;
    }

    public Instant getSavedAt() {
        return savedAt;
    }

    public Instant getRemovedAt() {
        return removedAt;
    }
}
