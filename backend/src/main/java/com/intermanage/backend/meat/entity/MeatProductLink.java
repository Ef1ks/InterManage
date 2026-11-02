package com.intermanage.backend.meat.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.product.entity.Product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Pozycja z WZ dostawcy przypisana do artykułu z kartoteki albo oznaczona jako bez artykułu ({@code product} pusty);
 * opis w V63__meat_deliveries.sql. Zmiana to znacznik na poprzednim wpisie.
 */
@Entity
@Table(name = "meat_product_links")
public class MeatProductLink {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(name = "supplier_id", nullable = false, updatable = false)
    private UUID supplierId;

    @Column(name = "document_key", nullable = false, updatable = false)
    private String documentKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", updatable = false)
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

    protected MeatProductLink() {
    }

    public MeatProductLink(UUID storeId, UUID supplierId, String documentKey, Product product, AppUser by) {
        this.storeId = storeId;
        this.supplierId = supplierId;
        this.documentKey = documentKey;
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

    public String getDocumentKey() {
        return documentKey;
    }

    public Product getProduct() {
        return product;
    }
}
