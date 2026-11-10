package com.intermanage.backend.document.entity;

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

/** Zmiana statusu weryfikacji przyjęcia (historia); pusty {@code status} = status zdjęty. */
@Entity
@Table(name = "document_verification_changes")
public class DocumentVerificationChange {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false, updatable = false)
    private Document document;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", updatable = false)
    private DocumentVerificationStatus status;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "changed_by", updatable = false)
    private AppUser changedBy;

    protected DocumentVerificationChange() {
    }

    public DocumentVerificationChange(Document document, DocumentVerificationStatus status, AppUser changedBy) {
        this.document = document;
        this.status = status;
        this.changedBy = changedBy;
        this.changedAt = Instant.now();
    }
}
