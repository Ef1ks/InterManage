package com.intermanage.backend.product.entity;

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
 * Ten sam towar w dwóch sklepach o różnych kodach: {@code itm8} w {@code storeId} to {@code linkedItm8} w
 * {@code linkedStoreId}. Działa w obie strony; strony zapisujemy w stałej kolejności sklepów
 * ({@code ArticleStoreLinkService.ordered}), żeby indeksy unikalne pilnowały jednego połączenia z każdym sklepem.
 */
@Entity
@Table(name = "article_store_links")
public class ArticleStoreLink {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false, updatable = false, length = 13)
    private String itm8;

    @Column(name = "linked_store_id", nullable = false, updatable = false)
    private UUID linkedStoreId;

    @Column(name = "linked_itm8", nullable = false, updatable = false, length = 13)
    private String linkedItm8;

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

    protected ArticleStoreLink() {
    }

    public ArticleStoreLink(UUID storeId, String itm8, UUID linkedStoreId, String linkedItm8, AppUser createdBy) {
        this.storeId = storeId;
        this.itm8 = itm8;
        this.linkedStoreId = linkedStoreId;
        this.linkedItm8 = linkedItm8;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public void delete(AppUser user) {
        deletedAt = Instant.now();
        deletedBy = user;
    }

    public void restore() {
        deletedAt = null;
        deletedBy = null;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public String getItm8() {
        return itm8;
    }

    public UUID getLinkedStoreId() {
        return linkedStoreId;
    }

    public String getLinkedItm8() {
        return linkedItm8;
    }
}
