package com.intermanage.backend.document.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Dokument MM sklepu docelowego przypięty do ruchu międzymagazynowego sklepu wysyłającego: potwierdzenie, że towar
 * wprowadzono w programie sklepu. Odpięcie to znacznik, ponowne przypięcie tworzy nowy wpis.
 */
@Entity
@Table(name = "document_transfer_receipts")
public class DocumentTransferReceipt {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_id", nullable = false, updatable = false)
    private DocumentTransferTarget target;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false, updatable = false)
    private Document document;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linked_by", updatable = false)
    private AppUser linkedBy;

    @Column(name = "linked_at", nullable = false, updatable = false)
    private Instant linkedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unlinked_by")
    private AppUser unlinkedBy;

    @Column(name = "unlinked_at")
    private Instant unlinkedAt;

    protected DocumentTransferReceipt() {
    }

    DocumentTransferReceipt(DocumentTransferTarget target, Document document, AppUser linkedBy) {
        this.target = target;
        this.document = document;
        this.linkedBy = linkedBy;
        this.linkedAt = Instant.now();
    }

    public void unlink(AppUser by) {
        unlinkedBy = by;
        unlinkedAt = Instant.now();
    }

    public boolean isActive() {
        return unlinkedAt == null;
    }

    public UUID getId() {
        return id;
    }

    public DocumentTransferTarget getTarget() {
        return target;
    }

    public Document getDocument() {
        return document;
    }

    public AppUser getLinkedBy() {
        return linkedBy;
    }

    public Instant getLinkedAt() {
        return linkedAt;
    }
}
