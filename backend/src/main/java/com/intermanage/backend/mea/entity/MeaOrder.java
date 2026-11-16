package com.intermanage.backend.mea.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.store.entity.Store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Zamówienie MEA zapisane do ATHOS: treść pliku V… (rekordy po 45 znaków), liczba pozycji z kartonami i kartonów.
 * Wysłane jest dopiero z {@code savedAt}, czyli po udanym zapisie pliku R… w folderze comrnis. Z {@code queuedAt} plik
 * czekał w kolejce sklepu na „Wyślij wszystkie” z komputera z dostępem do ATHOS.
 */
@Entity
@Table(name = "mea_orders")
public class MeaOrder {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(nullable = false, updatable = false)
    private String number;

    @Column(name = "file_name", nullable = false, updatable = false)
    private String fileName;

    @Column(nullable = false, updatable = false)
    private String content;

    @Column(nullable = false, updatable = false)
    private int positions;

    @Column(nullable = false, updatable = false)
    private int colis;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "saved_at")
    private Instant savedAt;

    /** Plik czeka w kolejce do wysłania z komputera z dostępem do ATHOS. */
    @Column(name = "queued_at", updatable = false)
    private Instant queuedAt;

    @Column(name = "removed_at")
    private Instant removedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "removed_by")
    private AppUser removedBy;

    /** Nowszy plik tego samego MEA w kolejce, który zastąpił ten. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "replaced_by_id")
    private MeaOrder replacedBy;

    protected MeaOrder() {
    }

    public MeaOrder(Store store, String number, String fileName, String content, int positions, int colis,
            AppUser createdBy) {
        this.store = store;
        this.number = number;
        this.fileName = fileName;
        this.content = content;
        this.positions = positions;
        this.colis = colis;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    /** Zamówienie do kolejki: zapisze je w folderze dopiero „Wyślij wszystkie”. */
    public static MeaOrder queued(Store store, String number, String fileName, String content, int positions,
            int colis, AppUser createdBy) {
        MeaOrder order = new MeaOrder(store, number, fileName, content, positions, colis, createdBy);
        order.queuedAt = order.createdAt;
        return order;
    }

    /** Czeka w kolejce: dodane do niej, niezapisane, nieusunięte i niezastąpione. */
    public boolean isPending() {
        return queuedAt != null && savedAt == null && removedAt == null && replacedBy == null;
    }

    public void remove(AppUser user) {
        removedAt = Instant.now();
        removedBy = user;
    }

    public void restore() {
        removedAt = null;
        removedBy = null;
    }

    public void replaceWith(MeaOrder newer) {
        replacedBy = newer;
    }

    public boolean isRemoved() {
        return removedAt != null;
    }

    public boolean isReplaced() {
        return replacedBy != null;
    }

    public void saved() {
        if (savedAt == null) {
            savedAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public String getNumber() {
        return number;
    }

    public String getFileName() {
        return fileName;
    }

    public int getPositions() {
        return positions;
    }

    public int getColis() {
        return colis;
    }

    public AppUser getCreatedBy() {
        return createdBy;
    }

    public Instant getSavedAt() {
        return savedAt;
    }

    public Instant getQueuedAt() {
        return queuedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getContent() {
        return content;
    }

    public Store getStore() {
        return store;
    }
}
