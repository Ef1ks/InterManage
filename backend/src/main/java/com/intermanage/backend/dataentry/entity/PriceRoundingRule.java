package com.intermanage.backend.dataentry.entity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;
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
 * Przedział cen z końcówką, do której zaokrąglamy cenę sugerowaną w górę (opis w V50__price_rounding_rules.sql).
 * Zapis listy zastępuje wszystkie reguły sklepu; poprzednie dostają znacznik {@code deletedAt}.
 */
@Entity
@Table(name = "price_rounding_rules")
public class PriceRoundingRule {

    private static final BigDecimal GROSZ = new BigDecimal("0.01");

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(name = "price_from", nullable = false, precision = 10, scale = 2, updatable = false)
    private BigDecimal priceFrom;

    @Column(name = "price_to", precision = 10, scale = 2, updatable = false)
    private BigDecimal priceTo;

    @Column(nullable = false, precision = 3, scale = 2, updatable = false)
    private BigDecimal ending;

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

    protected PriceRoundingRule() {
    }

    public PriceRoundingRule(Store store, BigDecimal priceFrom, BigDecimal priceTo, BigDecimal ending, AppUser by) {
        this.store = store;
        this.priceFrom = priceFrom;
        this.priceTo = priceTo;
        this.ending = ending;
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

    /** Cena w przedziale: od {@code priceFrom} włącznie do {@code priceTo} bez niej. */
    public boolean covers(BigDecimal price) {
        return price.compareTo(priceFrom) >= 0 && (priceTo == null || price.compareTo(priceTo) < 0);
    }

    /**
     * W górę do najbliższej ceny z końcówką: krok = końcówka + 1 gr, więc 0,49 daje ,49 i ,99 (3,12 → 3,49,
     * 3,60 → 3,99), a 0,99 zawsze ,99. Ten sam wzór we froncie ({@code roundPrice} w {@code rows.ts}).
     */
    public BigDecimal round(BigDecimal price) {
        BigDecimal step = ending.add(GROSZ);
        return price.add(GROSZ).divide(step, 0, RoundingMode.CEILING).multiply(step).subtract(GROSZ);
    }

    public UUID getId() {
        return id;
    }

    public BigDecimal getPriceFrom() {
        return priceFrom;
    }

    public BigDecimal getPriceTo() {
        return priceTo;
    }

    public BigDecimal getEnding() {
        return ending;
    }

    public AppUser getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
