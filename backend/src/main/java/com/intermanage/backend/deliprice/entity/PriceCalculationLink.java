package com.intermanage.backend.deliprice.entity;

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
 * Wyrób sklepu zaprzyjaźnionego ({@link #getSource()}) przypisany do artykułu kartoteki sklepu {@code store}
 * ({@link #getProduct()}), opis w V84__price_calculation_links.sql. Odpięcie to znacznik {@code deletedAt}.
 */
@Entity
@Table(name = "price_calculation_links")
public class PriceCalculationLink {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_product_id", nullable = false, updatable = false)
    private Product source;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    /** Z automatycznego przypisania: do sprawdzenia przez człowieka. */
    @Column(nullable = false, updatable = false)
    private boolean auto;

    /** Ktoś odpiął tę parę ręcznie, więc automat jej nie powtórzy. */
    @Column(nullable = false)
    private boolean rejected;

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

    protected PriceCalculationLink() {
    }

    public PriceCalculationLink(Store store, Product source, Product product, boolean auto, AppUser by) {
        this.store = store;
        this.source = source;
        this.product = product;
        this.auto = auto;
        this.createdBy = by;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    /** Odpięcie; {@code rejected} zapamiętuje, że to zła para, a nie cofnięcie automatu. */
    public void delete(AppUser by, boolean rejected) {
        deletedAt = Instant.now();
        deletedBy = by;
        this.rejected = rejected;
    }

    public void restore() {
        deletedAt = null;
        deletedBy = null;
        rejected = false;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public Product getSource() {
        return source;
    }

    public Product getProduct() {
        return product;
    }

    public boolean isAuto() {
        return auto;
    }
}
