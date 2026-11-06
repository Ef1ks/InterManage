package com.intermanage.backend.bakery.entity;

import java.math.BigDecimal;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.product.entity.Product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Ilość artykułu na WZ (opis w V85__bakery_checks.sql). */
@Entity
@Table(name = "bakery_wz_lines")
public class BakeryWzLine {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wz_id", nullable = false, updatable = false)
    private BakeryWz wz;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @Column(precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(name = "scanned_quantity", precision = 12, scale = 3)
    private BigDecimal scannedQuantity;

    @Column(nullable = false)
    private boolean manual;

    protected BakeryWzLine() {
    }

    public BakeryWzLine(BakeryWz wz, Product product) {
        this.wz = wz;
        this.product = product;
    }

    public void set(BigDecimal quantity, BigDecimal scannedQuantity, boolean manual) {
        this.quantity = quantity;
        this.scannedQuantity = scannedQuantity;
        this.manual = manual;
    }

    public UUID getId() {
        return id;
    }

    public Product getProduct() {
        return product;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getScannedQuantity() {
        return scannedQuantity;
    }

    public boolean isManual() {
        return manual;
    }
}
