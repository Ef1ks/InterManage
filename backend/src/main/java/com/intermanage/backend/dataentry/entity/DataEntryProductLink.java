package com.intermanage.backend.dataentry.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.product.entity.Product;
import com.intermanage.backend.store.entity.Store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Pozycja faktury dostawcy (kod z kolumny EAN, np. nazwa bez EAN-u) przypięta do artykułu z kartoteki (opis w
 * V53__data_entry_product_links.sql). Odpięcie to znacznik {@code deletedAt}.
 */
@Entity
@Table(name = "data_entry_product_links")
public class DataEntryProductLink {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(name = "seller_nip", nullable = false, updatable = false)
    private String sellerNip;

    @Column(name = "invoice_code", nullable = false, updatable = false)
    private String invoiceCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by")
    private AppUser deletedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected DataEntryProductLink() {
    }

    public DataEntryProductLink(Store store, String sellerNip, String invoiceCode, Product product, AppUser by) {
        this.store = store;
        this.sellerNip = sellerNip;
        this.invoiceCode = invoiceCode;
        this.product = product;
        this.createdBy = by;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public void delete(AppUser by) {
        deletedAt = Instant.now();
        deletedBy = by;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public String getInvoiceCode() {
        return invoiceCode;
    }

    public Product getProduct() {
        return product;
    }
}
