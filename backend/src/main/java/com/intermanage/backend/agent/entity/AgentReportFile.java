package com.intermanage.backend.agent.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Próba wgrania raportu z folderu sklepu przez agenta: plik wskazuje raport, nazwa i data zmiany na dysku. Wiersze
 * zostają jako historia (Import danych pokazuje ostatnie), a najnowszy wiersz pliku mówi serwerowi, czy o plik prosić.
 */
@Entity
@Table(name = "agent_report_files")
public class AgentReportFile {

    public enum Status {
        UPLOADING, IMPORTING, IMPORTED, FAILED
    }

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(name = "agent_id", updatable = false)
    private UUID agentId;

    @Column(nullable = false, length = 30, updatable = false)
    private String kind;

    @Column(name = "file_name", nullable = false, length = 260, updatable = false)
    private String fileName;

    @Column(name = "file_modified", nullable = false, updatable = false)
    private Instant fileModified;

    @Column(name = "file_size", nullable = false, updatable = false)
    private long fileSize;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(length = 2000)
    private String error;

    @Column(nullable = false)
    private boolean retryable;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "removed_from_disk_at")
    private Instant removedFromDiskAt;

    protected AgentReportFile() {
    }

    public AgentReportFile(UUID storeId, UUID agentId, String kind, String fileName, Instant fileModified,
            long fileSize) {
        this.storeId = storeId;
        this.agentId = agentId;
        this.kind = kind;
        this.fileName = fileName;
        this.fileModified = fileModified;
        this.fileSize = fileSize;
        this.status = Status.UPLOADING;
        this.startedAt = Instant.now();
    }

    public void importing() {
        status = Status.IMPORTING;
    }

    public void imported() {
        status = Status.IMPORTED;
        error = null;
        retryable = false;
        finishedAt = Instant.now();
    }

    public void failed(String message, boolean canRetry) {
        status = Status.FAILED;
        error = message == null ? null : message.length() > 2000 ? message.substring(0, 2000) : message;
        retryable = canRetry;
        finishedAt = Instant.now();
    }

    public void removedFromDisk() {
        if (removedFromDiskAt == null) {
            removedFromDiskAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getKind() {
        return kind;
    }

    public String getFileName() {
        return fileName;
    }

    public Instant getFileModified() {
        return fileModified;
    }

    public long getFileSize() {
        return fileSize;
    }

    public Status getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Instant getRemovedFromDiskAt() {
        return removedFromDiskAt;
    }
}
