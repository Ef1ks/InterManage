package com.intermanage.backend.pricecheck.entity;

import java.math.BigDecimal;
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
import jakarta.persistence.Table;

/**
 * Artykuł sprawdzony pod kątem za niskiej marży. Obowiązuje tylko dla zapisanych cen sprzedaży i zakupu; po zmianie
 * którejś artykuł wraca do kontroli. Cofnięcie to znacznik.
 */
@Entity
@Table(name = "article_margin_verifications")
public class ArticleMarginVerification {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(nullable = false, length = 13, updatable = false)
    private String itm8;

    @Column(name = "sale_price", nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal salePrice;

    @Column(name = "purchase_price", nullable = false, updatable = false, precision = 12, scale = 4)
    private BigDecimal purchasePrice;

    @Column(name = "margin_percent", nullable = false, updatable = false, precision = 7, scale = 2)
    private BigDecimal marginPercent;

    /** Data ostatniego przyjęcia PZ w chwili sprawdzenia; kolejna dostawa kończy weryfikację. */
    @Column(name = "last_purchase_date", updatable = false)
    private java.time.LocalDateTime lastPurchaseDate;

    @Column(length = 500, updatable = false)
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "verified_by", updatable = false)
    private AppUser verifiedBy;

    @Column(name = "verified_at", nullable = false, updatable = false)
    private Instant verifiedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by")
    private AppUser cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    protected ArticleMarginVerification() {
    }

    public ArticleMarginVerification(Store store, String itm8, BigDecimal salePrice, BigDecimal purchasePrice,
            BigDecimal marginPercent, java.time.LocalDateTime lastPurchaseDate, String note, AppUser verifiedBy) {
        this.lastPurchaseDate = lastPurchaseDate;
        this.store = store;
        this.itm8 = itm8;
        this.salePrice = salePrice;
        this.purchasePrice = purchasePrice;
        this.marginPercent = marginPercent;
        this.note = note;
        this.verifiedBy = verifiedBy;
        this.verifiedAt = Instant.now();
    }

    public void cancel(AppUser by) {
        cancelledBy = by;
        cancelledAt = Instant.now();
    }

    public void restore() {
        cancelledBy = null;
        cancelledAt = null;
    }

    /**
     * Weryfikacja dotyczy tych cen i tej samej ostatniej dostawy (porównanie bez względu na skalę, cena zakupu do
     * grosza): zmiana ceny albo kolejna dostawa przywraca artykuł do kontroli.
     */
    public boolean covers(BigDecimal salePrice, BigDecimal purchasePrice, java.time.LocalDateTime lastPurchaseDate) {
        return java.util.Objects.equals(this.lastPurchaseDate, lastPurchaseDate)
                && this.salePrice.compareTo(salePrice) == 0
                && this.purchasePrice.setScale(2, java.math.RoundingMode.HALF_UP)
                        .compareTo(purchasePrice.setScale(2, java.math.RoundingMode.HALF_UP)) == 0;
    }

    public UUID getId() {
        return id;
    }

    public Store getStore() {
        return store;
    }

    public String getItm8() {
        return itm8;
    }

    public BigDecimal getSalePrice() {
        return salePrice;
    }

    public BigDecimal getPurchasePrice() {
        return purchasePrice;
    }

    public BigDecimal getMarginPercent() {
        return marginPercent;
    }

    public String getNote() {
        return note;
    }

    public AppUser getVerifiedBy() {
        return verifiedBy;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }
}
