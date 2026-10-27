package com.intermanage.backend.entity;

import java.math.BigDecimal;
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
import jakarta.persistence.Table;

/** Pozycja zamówienia garmażu. Dział i kategoria są zapisane tekstem z chwili złożenia zamówienia. */
@Entity
@Table(name = "deli_order_items")
public class DeliOrderItem {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private DeliOrder order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @Column(name = "section_name", nullable = false, length = 100, updatable = false)
    private String sectionName;

    @Column(name = "category_name", nullable = false, length = 100, updatable = false)
    private String categoryName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private OrderUnit unit;

    @Column(name = "ordered_quantity", nullable = false, precision = 10, scale = 3, updatable = false)
    private BigDecimal orderedQuantity;

    /** Ilość po korekcie dostawcy; bez korekty równa {@link #orderedQuantity}. */
    @Column(nullable = false, precision = 10, scale = 3)
    private BigDecimal quantity;

    @Column(nullable = false, updatable = false)
    private int position;

    protected DeliOrderItem() {
    }

    DeliOrderItem(DeliOrder order, Product product, String sectionName, String categoryName, OrderUnit unit,
            BigDecimal quantity, int position) {
        this.order = order;
        this.product = product;
        this.sectionName = sectionName;
        this.categoryName = categoryName;
        this.unit = unit;
        this.orderedQuantity = quantity;
        this.quantity = quantity;
        this.position = position;
    }

    public UUID getId() {
        return id;
    }

    public Product getProduct() {
        return product;
    }

    public String getSectionName() {
        return sectionName;
    }

    public String getCategoryName() {
        return categoryName;
    }

    public OrderUnit getUnit() {
        return unit;
    }

    public BigDecimal getOrderedQuantity() {
        return orderedQuantity;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }
}
