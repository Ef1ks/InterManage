package com.intermanage.backend.backup.entity;

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
import jakarta.persistence.Table;

/** Jedna próba kopii zapasowej: automatyczna albo z „Zrób kopię teraz”. Zostaje jako historia. */
@Entity
@Table(name = "backup_runs")
public class BackupRun {

    /** Najdłuższy zapisywany opis błędu. */
    private static final int MAX_ERROR = 2000;

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private boolean manual;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BackupStatus status = BackupStatus.RUNNING;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt = Instant.now();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "started_by", updatable = false)
    private AppUser startedBy;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "google_email")
    private String googleEmail;

    @Column(name = "drive_file_id")
    private String driveFileId;

    @Column
    private String error;

    @Column(name = "trashed_at")
    private Instant trashedAt;

    protected BackupRun() {
    }

    public BackupRun(AppUser startedBy) {
        this.manual = startedBy != null;
        this.startedBy = startedBy;
    }

    public void succeed(String fileName, long sizeBytes, String googleEmail, String driveFileId) {
        this.status = BackupStatus.SUCCEEDED;
        this.finishedAt = Instant.now();
        this.fileName = fileName;
        this.sizeBytes = sizeBytes;
        this.googleEmail = googleEmail;
        this.driveFileId = driveFileId;
    }

    public void fail(String error) {
        this.status = BackupStatus.FAILED;
        this.finishedAt = Instant.now();
        this.error = error.length() > MAX_ERROR ? error.substring(0, MAX_ERROR) : error;
    }

    public void markTrashed() {
        this.trashedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public boolean isManual() {
        return manual;
    }

    public BackupStatus getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public AppUser getStartedBy() {
        return startedBy;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public String getFileName() {
        return fileName;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public String getGoogleEmail() {
        return googleEmail;
    }

    public String getDriveFileId() {
        return driveFileId;
    }

    public String getError() {
        return error;
    }

    public Instant getTrashedAt() {
        return trashedAt;
    }
}
