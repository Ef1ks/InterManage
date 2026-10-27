package com.intermanage.backend.entity;

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

    protected KsefInvoiceXml() {
    }

    public KsefInvoiceXml(UUID invoiceId, byte[] content) {
        this.invoiceId = invoiceId;
        this.content = content;
        this.downloadedAt = Instant.now();
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
}
