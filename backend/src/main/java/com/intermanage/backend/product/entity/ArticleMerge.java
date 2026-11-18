package com.intermanage.backend.product.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Artykuł {@code aliasItm8}, którego nie ma już w raporcie kartoteki, a jego EAN ({@code ean}) należy teraz do
 * {@code mainItm8} (multikod albo główny EAN): to jeden artykuł, więc karta {@code mainItm8} liczy też sprzedaż
 * i dokumenty {@code aliasItm8}. Wiersze zapisuje i kończy tylko {@code ArticleMergeRepository.refresh*}.
 */
@Entity
@Immutable
@Table(name = "article_merges")
public class ArticleMerge {

    @Id
    private UUID id;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(name = "alias_itm8", nullable = false, length = 13)
    private String aliasItm8;

    @Column(name = "main_itm8", nullable = false, length = 13)
    private String mainItm8;

    @Column(nullable = false, length = 14)
    private String ean;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    protected ArticleMerge() {
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public String getAliasItm8() {
        return aliasItm8;
    }

    public String getMainItm8() {
        return mainItm8;
    }

    public String getEan() {
        return ean;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }
}
