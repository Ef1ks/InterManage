package com.intermanage.backend.download.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Konto zna wersję pliku: pobrało ją albo potwierdziło „Mam już tę wersję”. Zapisuje go tylko
 * {@code DownloadFileSeenRepository.markSeen} (insert z {@code on conflict}), więc encja jest tylko do odczytu.
 */
@Entity
@Table(name = "download_file_seen")
public class DownloadFileSeen {

    @Id
    private UUID id;

    @Column(name = "file_id", nullable = false, insertable = false, updatable = false)
    private UUID fileId;

    @Column(name = "user_id", nullable = false, insertable = false, updatable = false)
    private UUID userId;

    @Column(name = "seen_at", nullable = false, insertable = false, updatable = false)
    private Instant seenAt;

    @Column(name = "downloaded_at", insertable = false, updatable = false)
    private Instant downloadedAt;

    protected DownloadFileSeen() {
    }

    public UUID getFileId() {
        return fileId;
    }
}
