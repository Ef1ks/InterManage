package com.intermanage.backend.bakery.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.product.entity.Product;
import com.intermanage.backend.supplier.entity.Supplier;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** Artykuł dostawcy odznaczony z domyślnej listy WZ; ponowne zaznaczenie to znacznik. */
@Entity
@Table(name = "bakery_hidden_articles")
public class BakeryHiddenArticle {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false, updatable = false)
    private Supplier supplier;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hidden_by", updatable = false)
    private AppUser hiddenBy;

    @Column(name = "hidden_at", nullable = false, updatable = false)
    private Instant hiddenAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "removed_by")
    private AppUser removedBy;

    @Column(name = "removed_at")
    private Instant removedAt;

    protected BakeryHiddenArticle() {
    }

    public BakeryHiddenArticle(Supplier supplier, Product product, AppUser hiddenBy) {
        this.supplier = supplier;
        this.product = product;
        this.hiddenBy = hiddenBy;
    }

    @PrePersist
    void onCreate() {
        hiddenAt = Instant.now();
    }

    public void remove(AppUser user) {
        removedAt = Instant.now();
        removedBy = user;
    }

    public Product getProduct() {
        return product;
    }
}
