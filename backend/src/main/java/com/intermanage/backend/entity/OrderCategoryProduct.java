package com.intermanage.backend.entity;

import java.time.Instant;
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

/** Produkt z kartoteki sklepu przypięty do kategorii formularza zamówienia. */
@Entity
@Table(name = "order_category_products")
public class OrderCategoryProduct {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false, updatable = false)
    private OrderCategory category;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @Column(nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_unit", nullable = false, length = 10)
    private OrderUnit orderUnit;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OrderCategoryProduct() {
    }

    OrderCategoryProduct(OrderCategory category, Product product, int position) {
        this.category = category;
        this.product = product;
        this.position = position;
        this.orderUnit = OrderUnit.defaultFor(product);
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Product getProduct() {
        return product;
    }

    public OrderUnit getOrderUnit() {
        return orderUnit;
    }

    public void setOrderUnit(OrderUnit orderUnit) {
        this.orderUnit = orderUnit;
    }

    public OrderCategory getCategory() {
        return category;
    }

    public int getPosition() {
        return position;
    }
}
