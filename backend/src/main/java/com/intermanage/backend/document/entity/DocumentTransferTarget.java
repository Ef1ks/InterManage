package com.intermanage.backend.document.entity;

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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Sklep, dla którego wystawiono ruch międzymagazynowy (MM). Sklep docelowy widzi dokument do wprowadzenia u siebie
 * i oznacza go jako wprowadzony ({@code enteredAt}). Cofnięcie ustawia {@code deletedAt}; zmiana sklepu to cofnięcie
 * tego wpisu i nowy wpis.
 */
@Entity
@Table(name = "document_transfer_targets")
public class DocumentTransferTarget {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false, updatable = false)
    private Document document;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_store_id", nullable = false, updatable = false)
    private Store targetStore;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "entered_by")
    private AppUser enteredBy;

    @Column(name = "entered_at")
    private Instant enteredAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by")
    private AppUser deletedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected DocumentTransferTarget() {
    }

    public DocumentTransferTarget(Document document, Store targetStore, AppUser createdBy) {
        this.document = document;
        this.targetStore = targetStore;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    /** Sklep docelowy wprowadził dokument u siebie; {@code entered = false} cofa oznaczenie. */
    public void markEntered(AppUser by, boolean entered) {
        enteredAt = entered ? Instant.now() : null;
        enteredBy = entered ? by : null;
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

    public Document getDocument() {
        return document;
    }

    public Store getTargetStore() {
        return targetStore;
    }

    public AppUser getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public AppUser getEnteredBy() {
        return enteredBy;
    }

    public Instant getEnteredAt() {
        return enteredAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
