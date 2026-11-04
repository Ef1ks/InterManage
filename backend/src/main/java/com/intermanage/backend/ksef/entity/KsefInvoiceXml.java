package com.intermanage.backend.ksef.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Oryginalny XML faktury pobrany z KSeF; zapisany raz, bo faktura w KSeF się nie zmienia. */
@Entity
@Table(name = "ksef_invoice_xml")
public class KsefInvoiceXml {

    @Id
    @Column(name = "invoice_id")
    private UUID invoiceId;

    @Column(nullable = false)
    private byte[] content;

    @Column(name = "downloaded_at", nullable = false)
    private Instant downloadedAt;

    /** Suma netto kaucji z pozycji faktury; {@code null}, dopóki XML nie został odczytany. */
    @Column(name = "deposit_net", precision = 16, scale = 2)
    private BigDecimal depositNet;

    protected KsefInvoiceXml() {
    }

    public KsefInvoiceXml(UUID invoiceId, byte[] content, BigDecimal depositNet) {
        this.invoiceId = invoiceId;
        this.content = content;
        this.downloadedAt = Instant.now();
        this.depositNet = depositNet;
    }

    public UUID getInvoiceId() {
        return invoiceId;
    }

    public byte[] getContent() {
        return content;
    }

    public Instant getDownloadedAt() {
        return downloadedAt;
    }

    public BigDecimal getDepositNet() {
        return depositNet;
    }

    public void setDepositNet(BigDecimal depositNet) {
        this.depositNet = depositNet;
    }
}
