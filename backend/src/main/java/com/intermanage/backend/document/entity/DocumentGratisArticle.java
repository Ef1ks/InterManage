package com.intermanage.backend.document.entity;

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
 * Artykuł z kartoteki sklepu, który w przeniesieniu stanów oznacza gratis: na minusie dokument może być gratisem, na
 * plusie coś jest nie tak. Usunięcie z listy to znacznik.
 */
@Entity
@Table(name = "document_gratis_articles")
public class DocumentGratisArticle {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "added_by", updatable = false)
    private AppUser addedBy;

    @Column(name = "added_at", nullable = false, updatable = false)
    private Instant addedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "removed_by")
    private AppUser removedBy;

    @Column(name = "removed_at")
    private Instant removedAt;

    protected DocumentGratisArticle() {
    }

    public DocumentGratisArticle(Product product, AppUser addedBy) {
        this.product = product;
        this.addedBy = addedBy;
    }

    @PrePersist
    void onCreate() {
        addedAt = Instant.now();
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

    public AppUser getAddedBy() {
        return addedBy;
    }

    public Instant getAddedAt() {
        return addedAt;
    }

    public Instant getRemovedAt() {
        return removedAt;
    }
}
