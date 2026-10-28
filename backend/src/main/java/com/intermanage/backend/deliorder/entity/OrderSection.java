package com.intermanage.backend.deliorder.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.store.entity.Store;

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

/** Dział formularza zamówienia garmażu (zakładka, np. Rzeźnia, Garmaż, Inne) w jednym sklepie. */
@Entity
@Table(name = "order_sections")
public class OrderSection {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(nullable = false, length = 100)
    private String name;

    /** Kolejność zakładek; nowe działy trafiają na koniec. */
    @Column(nullable = false)
    private int position;

    @OneToMany(mappedBy = "section", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position, id")
    private List<OrderCategory> categories = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OrderSection() {
    }

    public OrderSection(Store store, String name, int position) {
        this.store = store;
        this.name = name;
        this.position = position;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public OrderCategory addCategory(String categoryName) {
        int next = categories.stream().mapToInt(OrderCategory::getPosition).max().orElse(-1) + 1;
        OrderCategory category = new OrderCategory(this, categoryName, next);
        categories.add(category);
        return category;
    }

    /** Przestawia kategorię na miejsce {@code index} w dziale i numeruje kategorie od nowa. */
    public void moveCategory(OrderCategory category, int index) {
        categories.remove(category);
        categories.add(Math.min(index, categories.size()), category);
        for (int i = 0; i < categories.size(); i++) {
            categories.get(i).setPosition(i);
        }
    }

    public UUID getId() {
        return id;
    }

    public Store getStore() {
        return store;
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

    public List<OrderCategory> getCategories() {
        return categories;
    }
}
