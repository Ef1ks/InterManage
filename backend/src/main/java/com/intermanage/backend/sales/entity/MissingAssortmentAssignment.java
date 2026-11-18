package com.intermanage.backend.sales.entity;

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

/** Artykuł sklepu (ITM8) przypisany do kategorii brakującego asortymentu; zmiana i zdjęcie to znacznik. */
@Entity
@Table(name = "missing_assortment_assignments")
public class MissingAssortmentAssignment {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(nullable = false, updatable = false)
    private String itm8;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false, updatable = false)
    private MissingAssortmentCategory category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_by", updatable = false)
    private AppUser assignedBy;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "removed_by")
    private AppUser removedBy;

    @Column(name = "removed_at")
    private Instant removedAt;

    protected MissingAssortmentAssignment() {
    }

    public MissingAssortmentAssignment(Store store, String itm8, MissingAssortmentCategory category,
            AppUser assignedBy) {
        this.store = store;
        this.itm8 = itm8;
        this.category = category;
        this.assignedBy = assignedBy;
    }

    @PrePersist
    void onCreate() {
        assignedAt = Instant.now();
    }

    public void remove(AppUser user) {
        removedAt = Instant.now();
        removedBy = user;
    }

    public UUID getId() {
        return id;
    }

    public String getItm8() {
        return itm8;
    }

    public MissingAssortmentCategory getCategory() {
        return category;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }
}
