package com.intermanage.backend.collector.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.data.domain.Persistable;

import com.intermanage.backend.auth.entity.AppUser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/** Promocje ustawione na kolektorze. Id nadaje kolektor, więc encja sama mówi, czy jest nowa. */
@Entity
@Table(name = "collector_promotions")
public class CollectorPromotion implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "collector_id", nullable = false, updatable = false)
    private StoreCollector collector;

    @Column(name = "started_at", updatable = false)
    private LocalDateTime startedAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "start_date", updatable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false, updatable = false)
    private LocalDate endDate;

    @Column(name = "line_count", nullable = false, updatable = false)
    private int lineCount;

    @Column(name = "macro_requested", nullable = false, updatable = false)
    private boolean macroRequested;

    @Column(name = "macro_job_id")
    private UUID macroJobId;

    @Column(name = "macro_error", length = 500)
    private String macroError;

    @Column(name = "labels_file_id")
    private UUID labelsFileId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inserted_by")
    private AppUser insertedBy;

    @Column(name = "inserted_at")
    private Instant insertedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by")
    private AppUser deletedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Transient
    private boolean stored;

    protected CollectorPromotion() {
    }

    public CollectorPromotion(UUID id, StoreCollector collector, LocalDateTime startedAt, LocalDate startDate,
            LocalDate endDate, int lineCount, boolean macroRequested) {
        this.id = id;
        this.storeId = collector.getStore().getId();
        this.collector = collector;
        this.startedAt = startedAt;
        this.receivedAt = Instant.now();
        this.startDate = startDate;
        this.endDate = endDate;
        this.lineCount = lineCount;
        this.macroRequested = macroRequested;
    }

    @PostLoad
    @PostPersist
    void markStored() {
        stored = true;
    }

    @Override
    public boolean isNew() {
        return !stored;
    }

    public void macroStarted(UUID jobId) {
        macroJobId = jobId;
        macroError = null;
    }

    public void macroFailed(String message) {
        macroError = message == null ? "Makro nie ruszyło" : message.length() > 500 ? message.substring(0, 500)
                : message;
    }

    public void labelsQueued(UUID fileId) {
        labelsFileId = fileId;
    }

    public void markInserted(AppUser by) {
        insertedAt = Instant.now();
        insertedBy = by;
    }

    public void markDeleted(AppUser by) {
        deletedAt = Instant.now();
        deletedBy = by;
    }

    public void restore() {
        deletedAt = null;
        deletedBy = null;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public StoreCollector getCollector() {
        return collector;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public int getLineCount() {
        return lineCount;
    }

    public boolean isMacroRequested() {
        return macroRequested;
    }

    public UUID getMacroJobId() {
        return macroJobId;
    }

    public String getMacroError() {
        return macroError;
    }

    public UUID getLabelsFileId() {
        return labelsFileId;
    }

    public AppUser getInsertedBy() {
        return insertedBy;
    }

    public Instant getInsertedAt() {
        return insertedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
