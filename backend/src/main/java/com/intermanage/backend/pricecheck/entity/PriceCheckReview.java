package com.intermanage.backend.pricecheck.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.document.entity.Document;
import com.intermanage.backend.store.entity.Store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Zmiana statusu wyjaśniania przyjęcia PZ z ceną wyższą niż baza. Obowiązuje najnowszy wpis bez znacznika
 * {@code deletedAt}; cofnięcie zmiany przywraca poprzedni status.
 */
@Entity
@Table(name = "price_check_reviews")
public class PriceCheckReview {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false, updatable = false)
    private Document document;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private PriceCheckStatus status;

    @Column(length = 1000, updatable = false)
    private String note;

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

    protected PriceCheckReview() {
    }

    public PriceCheckReview(Store store, Document document, PriceCheckStatus status, String note, AppUser createdBy) {
        this.store = store;
        this.document = document;
        this.status = status;
        this.note = note;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
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

    public PriceCheckStatus getStatus() {
        return status;
    }

    public String getNote() {
        return note;
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
