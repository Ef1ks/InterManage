package com.intermanage.backend.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Potwierdzona różnica między wartością zakupu przyjęcia a sumą netto jego faktur KSeF. Obowiązuje tylko dla
 * zapisanych kwot; cofnięcie to znacznik.
 */
@Entity
@Table(name = "document_amount_confirmations")
public class DocumentAmountConfirmation {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false, updatable = false)
    private Document document;

    @Column(name = "invoice_net", nullable = false, updatable = false, precision = 16, scale = 2)
    private BigDecimal invoiceNet;

    @Column(name = "document_value", nullable = false, updatable = false, precision = 16, scale = 2)
    private BigDecimal documentValue;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "confirmed_by", updatable = false)
    private AppUser confirmedBy;

    @Column(name = "confirmed_at", nullable = false, updatable = false)
    private Instant confirmedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by")
    private AppUser cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    protected DocumentAmountConfirmation() {
    }

    public DocumentAmountConfirmation(Document document, BigDecimal invoiceNet, BigDecimal documentValue,
            AppUser confirmedBy) {
        this.document = document;
        this.invoiceNet = invoiceNet;
        this.documentValue = documentValue;
        this.confirmedBy = confirmedBy;
        this.confirmedAt = Instant.now();
    }

    public void cancel(AppUser by) {
        cancelledBy = by;
        cancelledAt = Instant.now();
    }

    /** Potwierdzenie dotyczy tych kwot (porównanie bez względu na skalę). */
    public boolean covers(BigDecimal invoiceNet, BigDecimal documentValue) {
        return this.invoiceNet.compareTo(invoiceNet) == 0 && this.documentValue.compareTo(documentValue) == 0;
    }

    public UUID getId() {
        return id;
    }

    public Document getDocument() {
        return document;
    }

    public AppUser getConfirmedBy() {
        return confirmedBy;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }
}
