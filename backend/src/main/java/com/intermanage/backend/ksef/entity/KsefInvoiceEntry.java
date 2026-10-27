package com.intermanage.backend.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Ręczne oznaczenie faktury jako wprowadzonej w sklepie (gdy numeru nie ma w PZ albo różni się zapisem). Faktura jest
 * wspólna dla firmy, oznaczenie należy do sklepu; cofnięcie usuwa wpis.
 */
@Entity
@Table(name = "ksef_invoice_entries")
public class KsefInvoiceEntry {

    @Embeddable
    public record Key(
            @Column(name = "store_id") UUID storeId,
            @Column(name = "invoice_id") UUID invoiceId) {
    }

    @EmbeddedId
    private Key id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "entered_by")
    private AppUser enteredBy;

    @Column(name = "entered_at", nullable = false)
    private Instant enteredAt;

    protected KsefInvoiceEntry() {
    }

    public KsefInvoiceEntry(UUID storeId, UUID invoiceId, AppUser by) {
        this.id = new Key(storeId, invoiceId);
        this.enteredBy = by;
        this.enteredAt = Instant.now();
    }

    public Key getId() {
        return id;
    }

    public AppUser getEnteredBy() {
        return enteredBy;
    }

    public Instant getEnteredAt() {
        return enteredAt;
    }
}
