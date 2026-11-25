package com.intermanage.backend.collector.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.store.entity.ProtocolKind;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Protokół zapisany z listy kolektora; cofnięcie oznaczenia to znacznik. */
@Entity
@Table(name = "collector_list_protocols")
public class CollectorListProtocol {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "list_id", nullable = false, updatable = false)
    private UUID listId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 30)
    private ProtocolKind kind;

    @Column(name = "file_name", nullable = false, updatable = false, length = 200)
    private String fileName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "undone_by")
    private AppUser undoneBy;

    @Column(name = "undone_at")
    private Instant undoneAt;

    protected CollectorListProtocol() {
    }

    public CollectorListProtocol(UUID listId, ProtocolKind kind, String fileName, AppUser createdBy) {
        this.listId = listId;
        this.kind = kind;
        this.fileName = fileName;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public void undo(AppUser by) {
        undoneAt = Instant.now();
        undoneBy = by;
    }

    public UUID getId() {
        return id;
    }

    public UUID getListId() {
        return listId;
    }

    public ProtocolKind getKind() {
        return kind;
    }

    public String getFileName() {
        return fileName;
    }

    public AppUser getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUndoneAt() {
        return undoneAt;
    }
}
