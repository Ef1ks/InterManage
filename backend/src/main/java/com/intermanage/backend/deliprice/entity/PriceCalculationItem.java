package com.intermanage.backend.deliprice.entity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.document.entity.Document;
import com.intermanage.backend.product.entity.Product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Składnik kalkulacji: produkt z kartoteki, jego ilość w jednostce {@link Product#getCostUnit()} i zapamiętana cena
 * tej jednostki (z ostatniej dostawy albo z kartoteki). Cenę zmienia dopiero "Odśwież ceny".
 */
@Entity
@Table(name = "price_calculation_items")
public class PriceCalculationItem {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "calculation_id", nullable = false, updatable = false)
    private PriceCalculation calculation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(name = "unit_cost", precision = 14, scale = 4)
    private BigDecimal unitCost;

    @Enumerated(EnumType.STRING)
    @Column(name = "price_source", length = 10)
    private PriceSource priceSource;

    /** Przyjęcie PZ, z którego jest cena ({@link PriceSource#DELIVERY}). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "price_document_id")
    private Document priceDocument;

    @Column(name = "priced_at")
    private Instant pricedAt;

    protected PriceCalculationItem() {
    }

    PriceCalculationItem(PriceCalculation calculation, Product product, int position, BigDecimal quantity,
            IngredientPrice price) {
        this.calculation = calculation;
        this.product = product;
        this.position = position;
        this.quantity = quantity;
        setPrice(price);
    }

    public void setPrice(IngredientPrice price) {
        unitCost = price.unitCost();
        priceSource = price.source();
        priceDocument = price.document();
        pricedAt = Instant.now();
    }

    /** Cena zapamiętana przy dodaniu albo ostatnim odświeżeniu - zostaje przy zmianie samej ilości. */
    IngredientPrice price() {
        return new IngredientPrice(unitCost, priceSource, priceDocument);
    }

    /** Koszt składnika; {@code null}, gdy nie ma ceny. */
    public BigDecimal getCost() {
        return unitCost == null ? null : unitCost.multiply(quantity).setScale(4, RoundingMode.HALF_UP);
    }

    public UUID getId() {
        return id;
    }

    public Product getProduct() {
        return product;
    }

    public int getPosition() {
        return position;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitCost() {
        return unitCost;
    }

    public PriceSource getPriceSource() {
        return priceSource;
    }

    public Document getPriceDocument() {
        return priceDocument;
    }

    public Instant getPricedAt() {
        return pricedAt;
    }
}
