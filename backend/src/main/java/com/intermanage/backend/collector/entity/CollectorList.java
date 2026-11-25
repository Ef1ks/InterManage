package com.intermanage.backend.collector.entity;

import java.time.Instant;
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

/** Lista zeskanowana na kolektorze. Id nadaje kolektor, więc encja sama mówi, czy jest nowa. */
@Entity
@Table(name = "collector_lists")
public class CollectorList implements Persistable<UUID> {

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

    @Column(name = "line_count", nullable = false, updatable = false)
    private int lineCount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by")
    private AppUser deletedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Transient
    private boolean stored;

    protected CollectorList() {
    }

    public CollectorList(UUID id, StoreCollector collector, LocalDateTime startedAt, int lineCount) {
        this.id = id;
        this.storeId = collector.getStore().getId();
        this.collector = collector;
        this.startedAt = startedAt;
        this.receivedAt = Instant.now();
        this.lineCount = lineCount;
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

    public int getLineCount() {
        return lineCount;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
