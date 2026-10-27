package com.intermanage.backend.deliorder.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.product.entity.Product;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** Kategoria w dziale formularza zamówienia (np. Wędliny w Rzeźni) z listą produktów do zamawiania. */
@Entity
@Table(name = "order_categories")
public class OrderCategory {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "section_id", nullable = false, updatable = false)
    private OrderSection section;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private int position;

    @OneToMany(mappedBy = "category", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position, id")
    private List<OrderCategoryProduct> products = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OrderCategory() {
    }

    OrderCategory(OrderSection section, String name, int position) {
        this.section = section;
        this.name = name;
        this.position = position;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    /** Dodaje produkt na koniec kategorii; produkt, który już w niej jest, zostaje na swoim miejscu. */
    public OrderCategoryProduct addProduct(Product product) {
        for (OrderCategoryProduct existing : products) {
            if (existing.getProduct().getId().equals(product.getId())) {
                return existing;
            }
        }
        int next = products.stream().mapToInt(OrderCategoryProduct::getPosition).max().orElse(-1) + 1;
        OrderCategoryProduct item = new OrderCategoryProduct(this, product, next);
        products.add(item);
        return item;
    }

    public Optional<OrderCategoryProduct> findProduct(UUID itemId) {
        return products.stream().filter(item -> item.getId().equals(itemId)).findFirst();
    }

    public boolean removeProduct(UUID itemId) {
        return products.removeIf(item -> item.getId().equals(itemId));
    }

    public UUID getId() {
        return id;
    }

    public OrderSection getSection() {
        return section;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getPosition() {
        return position;
    }

    public List<OrderCategoryProduct> getProducts() {
        return products;
    }
}
