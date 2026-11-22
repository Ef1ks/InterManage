package com.intermanage.backend.feedback.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.store.entity.Store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Zgłoszenie poprawki albo błędu od pracownika, z miejscem w aplikacji, w którym je zrobił. */
@Entity
@Table(name = "feedback_reports")
public class FeedbackReport {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", updatable = false)
    private Store store;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private FeedbackKind kind;

    @Column(nullable = false, updatable = false)
    private String description;

    @Column(name = "page_url", updatable = false)
    private String pageUrl;

    @Column(name = "page_label", updatable = false)
    private String pageLabel;

    @Column(name = "user_agent", updatable = false)
    private String userAgent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FeedbackStatus status = FeedbackStatus.NEW;

    @Column(name = "owner_note")
    private String ownerNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "status_changed_by")
    private AppUser statusChangedBy;

    @Column(name = "status_changed_at")
    private Instant statusChangedAt;

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

    protected FeedbackReport() {
    }

    public FeedbackReport(Store store, FeedbackKind kind, String description, String pageUrl, String pageLabel,
            String userAgent, AppUser createdBy) {
        this.store = store;
        this.kind = kind;
        this.description = description;
        this.pageUrl = pageUrl;
        this.pageLabel = pageLabel;
        this.userAgent = userAgent;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public void changeStatus(FeedbackStatus status, String note, AppUser user) {
        this.status = status;
        this.ownerNote = note;
        this.statusChangedBy = user;
        this.statusChangedAt = Instant.now();
    }

    public void markDeleted(AppUser user) {
        deletedAt = Instant.now();
        deletedBy = user;
    }

    public void restore() {
        deletedAt = null;
        deletedBy = null;
    }

    public UUID getId() {
        return id;
    }

    public Store getStore() {
        return store;
    }

    public FeedbackKind getKind() {
        return kind;
    }

    public String getDescription() {
        return description;
    }

    public String getPageUrl() {
        return pageUrl;
    }

    public String getPageLabel() {
        return pageLabel;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public FeedbackStatus getStatus() {
        return status;
    }

    public String getOwnerNote() {
        return ownerNote;
    }

    public AppUser getStatusChangedBy() {
        return statusChangedBy;
    }

    public Instant getStatusChangedAt() {
        return statusChangedAt;
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
