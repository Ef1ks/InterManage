package com.intermanage.backend.stocktake.entity;

import java.time.Instant;
import java.time.LocalDate;
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

/** Inwentura sklepu rozpoznana po numerze i dacie z nagłówka raportu. Usunięcie to znacznik. */
@Entity
@Table(name = "stocktakes")
public class Stocktake {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(name = "number", nullable = false, length = 20, updatable = false)
    private String number;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "stocktake_date", nullable = false, updatable = false)
    private LocalDate stocktakeDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by")
    private AppUser deletedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Stocktake() {
    }

    public Stocktake(Store store, String number, String name, LocalDate stocktakeDate) {
        this.store = store;
        this.number = number;
        this.name = name;
        this.stocktakeDate = stocktakeDate;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public void delete(AppUser by) {
        deletedAt = Instant.now();
        deletedBy = by;
    }

    public void restore() {
        deletedAt = null;
        deletedBy = null;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public String getNumber() {
        return number;
    }

    public String getName() {
        return name;
    }

    public LocalDate getStocktakeDate() {
        return stocktakeDate;
    }
}
