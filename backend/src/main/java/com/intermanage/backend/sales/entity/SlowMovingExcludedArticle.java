package com.intermanage.backend.sales.entity;

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

/** Artykuł pominięty w raporcie niesprzedających się; przywrócenie do raportu to znacznik. */
@Entity
@Table(name = "slow_moving_excluded_articles")
public class SlowMovingExcludedArticle {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "excluded_by", updatable = false)
    private AppUser excludedBy;

    @Column(name = "excluded_at", nullable = false, updatable = false)
    private Instant excludedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "removed_by")
    private AppUser removedBy;

    @Column(name = "removed_at")
    private Instant removedAt;

    protected SlowMovingExcludedArticle() {
    }

    public SlowMovingExcludedArticle(Product product, AppUser excludedBy) {
        this.product = product;
        this.excludedBy = excludedBy;
    }

    @PrePersist
    void onCreate() {
        excludedAt = Instant.now();
    }

    public void remove(AppUser user) {
        removedAt = Instant.now();
        removedBy = user;
    }

    public UUID getId() {
        return id;
    }

    public Product getProduct() {
        return product;
    }

    public AppUser getExcludedBy() {
        return excludedBy;
    }

    public Instant getExcludedAt() {
        return excludedAt;
    }
}
