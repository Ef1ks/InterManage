package com.intermanage.backend.product.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Zmiana ceny sprzedaży albo promocji artykułu ze stanem przed i po (V70). Powstaje, gdy plik TRACEART albo import
 * kartoteki zmienia cenę; wpisy nie są zmieniane ani usuwane.
 */
@Entity
@Table(name = "product_price_changes")
public class ProductPriceChange {

    /** Skąd przyszła cena: plik z kasy (TRACEART) albo raport kartoteki. */
    public enum Source {
        TILL, REPORT
    }

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @Column(name = "changed_at", updatable = false)
    private LocalDateTime changedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private Source source;

    @Column(name = "previous_sale_price", precision = 12, scale = 2, updatable = false)
    private BigDecimal previousSalePrice;

    @Column(name = "sale_price", precision = 12, scale = 2, updatable = false)
    private BigDecimal salePrice;

    @Column(name = "previous_promo_price", precision = 12, scale = 2, updatable = false)
    private BigDecimal previousPromoPrice;

    @Column(name = "promo_price", precision = 12, scale = 2, updatable = false)
    private BigDecimal promoPrice;

    @Column(name = "promo_from", updatable = false)
    private LocalDate promoFrom;

    @Column(name = "promo_to", updatable = false)
    private LocalDate promoTo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "price_file_id", updatable = false)
    private PriceFileImport priceFile;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_import_id", updatable = false)
    private ProductImport productImport;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ProductPriceChange() {
    }

    private ProductPriceChange(Product product, Source source, LocalDateTime changedAt, BigDecimal previousSalePrice,
            BigDecimal previousPromoPrice) {
        this.storeId = product.getStore().getId();
        this.product = product;
        this.source = source;
        this.changedAt = changedAt;
        this.previousSalePrice = previousSalePrice;
        this.previousPromoPrice = previousPromoPrice;
        this.salePrice = product.getSalePrice();
        this.promoPrice = product.getPromoPrice();
        this.promoFrom = product.getPromoFrom();
        this.promoTo = product.getPromoTo();
    }

    /** Plik TRACEART zmienił cenę albo promocję; stan po zmianie z artykułu. */
    public static ProductPriceChange fromPriceFile(Product product, PriceFileImport file, BigDecimal previousSalePrice,
            BigDecimal previousPromoPrice) {
        ProductPriceChange change = new ProductPriceChange(product, Source.TILL, file.getFileTime(),
                previousSalePrice, previousPromoPrice);
        change.priceFile = file;
        return change;
    }

    /** Import kartoteki zmienił cenę sprzedaży. */
    public static ProductPriceChange fromReport(Product product, ProductImport productImport,
            BigDecimal previousSalePrice) {
        ProductPriceChange change = new ProductPriceChange(product, Source.REPORT,
                productImport.getReportGeneratedAt(), previousSalePrice, product.getPromoPrice());
        change.productImport = productImport;
        return change;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
