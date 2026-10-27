package com.intermanage.backend.entity;

import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Zmiana przypisania EAN wykryta przy imporcie. {@code previousEan}: ten ITM8 miał wcześniej inny EAN.
 * {@code previousProduct}: ten EAN należał wcześniej do innego ITM8. Co najmniej jedno z nich jest ustawione.
 */
@Entity
@Table(name = "product_ean_changes")
public class ProductEanChange {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "import_id", nullable = false, updatable = false)
    private ProductImport productImport;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @Column(nullable = false, length = 14, updatable = false)
    private String ean;

    @Column(name = "previous_ean", length = 14, updatable = false)
    private String previousEan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "previous_product_id", updatable = false)
    private Product previousProduct;

    protected ProductEanChange() {
    }

    public ProductEanChange(ProductImport productImport, Product product, String ean, String previousEan,
            Product previousProduct) {
        this.productImport = productImport;
        this.product = product;
        this.ean = ean;
        this.previousEan = previousEan;
        this.previousProduct = previousProduct;
    }

    public Product getProduct() {
        return product;
    }

    public String getEan() {
        return ean;
    }

    public String getPreviousEan() {
        return previousEan;
    }

    public Product getPreviousProduct() {
        return previousProduct;
    }
}
