package com.intermanage.backend.collector.entity;

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

/**
 * Inwentaryzacja albo zamówienie z kolektora w historii: wgrane (plik zapisany dla programu sklepu) albo czekające
 * (przygotowane na później, plik powstaje z historii). Usunięcie to znacznik.
 */
@Entity
@Table(name = "collector_files")
public class CollectorFile {

    public enum Kind {
        INVENTORY, ORDER
    }

    public enum Status {
        WAITING, LOADED
    }

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Kind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.WAITING;

    private String note;

    @Column(name = "file_name")
    private String fileName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "loaded_by")
    private AppUser loadedBy;

    @Column(name = "loaded_at")
    private Instant loadedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by")
    private AppUser deletedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected CollectorFile() {
    }

    public CollectorFile(UUID storeId, Kind kind, String note, AppUser createdBy) {
        this.storeId = storeId;
        this.kind = kind;
        this.note = note;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    /** Plik zapisany dla programu sklepu (także ponownie, wtedy z nową nazwą). */
    public void loaded(String fileName, AppUser by) {
        this.status = Status.LOADED;
        this.fileName = fileName;
        this.loadedBy = by;
        this.loadedAt = Instant.now();
    }

    public void markDeleted(AppUser by) {
        deletedAt = Instant.now();
        deletedBy = by;
    }

    public void restore() {
        deletedAt = null;
        deletedBy = null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public Kind getKind() {
        return kind;
    }

    public Status getStatus() {
        return status;
    }

    public String getNote() {
        return note;
    }

    public String getFileName() {
        return fileName;
    }

    public AppUser getLoadedBy() {
        return loadedBy;
    }

    public Instant getLoadedAt() {
        return loadedAt;
    }

    public AppUser getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
