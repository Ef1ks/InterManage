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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Plik faktury spoza KSeF (PDF albo zdjęcie) dołączony do {@link DocumentManualInvoice}. Wymiana i usunięcie to
 * znacznik. Listy czytają tylko opis pliku ({@code DocumentManualInvoiceFileRepository.Summary}), bez treści.
 */
@Entity
@Table(name = "document_manual_invoice_files")
public class DocumentManualInvoiceFile {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "manual_invoice_id", nullable = false, updatable = false)
    private DocumentManualInvoice invoice;

    @Column(name = "file_name", nullable = false, length = 255, updatable = false)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 100, updatable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private int sizeBytes;

    @Column(nullable = false, updatable = false)
    private byte[] content;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "removed_by")
    private AppUser removedBy;

    @Column(name = "removed_at")
    private Instant removedAt;

    protected DocumentManualInvoiceFile() {
    }

    public DocumentManualInvoiceFile(DocumentManualInvoice invoice, String fileName, String contentType,
            byte[] content, AppUser createdBy) {
        this.invoice = invoice;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = content.length;
        this.content = content;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public void markRemoved(AppUser by) {
        removedAt = Instant.now();
        removedBy = by;
    }

    public void restore() {
        removedAt = null;
        removedBy = null;
    }

    public UUID getId() {
        return id;
    }

    public DocumentManualInvoice getInvoice() {
        return invoice;
    }

    public String getFileName() {
        return fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public int getSizeBytes() {
        return sizeBytes;
    }

    public byte[] getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getRemovedAt() {
        return removedAt;
    }
}
