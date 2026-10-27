package com.intermanage.backend.deliorder.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.document.entity.Document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Dokument ruchu międzymagazynowego, na którym jedzie zamówienie garmażu. Odpięcie ustawia {@link #unlinkedAt};
 * wpis zostaje w historii, a ponowne przypięcie tworzy nowy.
 */
@Entity
@Table(name = "deli_order_documents")
public class DeliOrderDocument {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private DeliOrder order;

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

    protected DeliOrderDocument() {
    }

    public DeliOrderDocument(DeliOrder order, Document document, AppUser linkedBy) {
        this.order = order;
        this.document = document;
        this.linkedBy = linkedBy;
        this.linkedAt = Instant.now();
    }

    public void unlink(AppUser by) {
        unlinkedBy = by;
        unlinkedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public DeliOrder getOrder() {
        return order;
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
