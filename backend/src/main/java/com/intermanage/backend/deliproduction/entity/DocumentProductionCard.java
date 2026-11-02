package com.intermanage.backend.deliproduction.entity;

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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** Karta produkcji przypięta do ruchu międzymagazynowego (MM). Odpięcie to znacznik. */
@Entity
@Table(name = "document_production_cards")
public class DocumentProductionCard {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false, updatable = false)
    private Document document;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "card_id", nullable = false, updatable = false)
    private ProductionCard card;

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

    protected DocumentProductionCard() {
    }

    public DocumentProductionCard(Document document, ProductionCard card, AppUser by) {
        this.document = document;
        this.card = card;
        this.linkedBy = by;
    }

    @PrePersist
    void onCreate() {
        linkedAt = Instant.now();
    }

    public void unlink(AppUser by) {
        unlinkedAt = Instant.now();
        unlinkedBy = by;
    }

    public UUID getId() {
        return id;
    }

    public Document getDocument() {
        return document;
    }

    public ProductionCard getCard() {
        return card;
    }

    public AppUser getLinkedBy() {
        return linkedBy;
    }

    public Instant getLinkedAt() {
        return linkedAt;
    }
}
