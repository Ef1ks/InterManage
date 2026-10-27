package com.intermanage.backend.document.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.ksef.entity.KsefInvoice;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Dokument sklepu (przyjęcie PZ, korekta od dostawcy) przypięty ręcznie do faktury z KSeF. Odpięcie to znacznik,
 * ponowne przypięcie tworzy nowy wpis.
 */
@Entity
@Table(name = "document_ksef_invoices")
public class DocumentKsefInvoice {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false, updatable = false)
    private Document document;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false, updatable = false)
    private KsefInvoice invoice;

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

    protected DocumentKsefInvoice() {
    }

    public DocumentKsefInvoice(Document document, KsefInvoice invoice, AppUser linkedBy) {
        this.document = document;
        this.invoice = invoice;
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

    public Document getDocument() {
        return document;
    }

    public KsefInvoice getInvoice() {
        return invoice;
    }

    public AppUser getLinkedBy() {
        return linkedBy;
    }

    public Instant getLinkedAt() {
        return linkedAt;
    }
}
