package com.intermanage.backend.leaflet.entity;

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
 * Artykuł z kartoteki wybrany ręcznie dla EAN-u z gazetki (opis w V75__promo_leaflets.sql). {@code previous} to
 * przypięcie zastąpione tym wpisem: usunięcie wpisu je przywraca.
 */
@Entity
@Table(name = "promo_leaflet_links")
public class PromoLeafletLink {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(nullable = false, length = 20, updatable = false)
    private String ean;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "previous_id", updatable = false)
    private PromoLeafletLink previous;

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

    protected PromoLeafletLink() {
    }

    public PromoLeafletLink(Store store, String ean, Product product, PromoLeafletLink previous, AppUser by) {
        this.store = store;
        this.ean = ean;
        this.product = product;
        this.previous = previous;
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

    public void restore() {
        deletedAt = null;
        deletedBy = null;
    }

    public UUID getId() {
        return id;
    }

    public String getEan() {
        return ean;
    }

    public Product getProduct() {
        return product;
    }

    public PromoLeafletLink getPrevious() {
        return previous;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
