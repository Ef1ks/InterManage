package com.intermanage.backend.document.entity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

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
 * Ręczne połączenie w porównaniu przyjęcia PZ z fakturą: pozycje faktury ({@code invoiceKeys}, "ean:…" albo
 * "name:…") i artykuły przyjęcia ({@code itm8s}) porównywane razem. Rozłączenie to znacznik.
 */
@Entity
@Table(name = "invoice_comparison_matches")
public class InvoiceComparisonMatch {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false, updatable = false)
    private Document document;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "invoice_keys", nullable = false, updatable = false, columnDefinition = "text[]")
    private List<String> invoiceKeys;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "itm8s", nullable = false, updatable = false, columnDefinition = "text[]")
    private List<String> itm8s;

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

    protected InvoiceComparisonMatch() {
    }

    public InvoiceComparisonMatch(Document document, List<String> invoiceKeys, List<String> itm8s, AppUser createdBy) {
        this.document = document;
        this.invoiceKeys = List.copyOf(invoiceKeys);
        this.itm8s = List.copyOf(itm8s);
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

    public List<String> getInvoiceKeys() {
        return invoiceKeys;
    }

    public List<String> getItm8s() {
        return itm8s;
    }

    public Instant getRemovedAt() {
        return removedAt;
    }
}
