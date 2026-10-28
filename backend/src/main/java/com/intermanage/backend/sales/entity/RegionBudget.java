package com.intermanage.backend.sales.entity;

import java.math.BigDecimal;
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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * Budżet rejonu nomenklatury w sklepie: docelowa marża (% od sprzedaży netto) i najwyższe dopuszczalne straty
 * (likwidacje, % od sprzedaży brutto). Puste = bez budżetu. Ustawia go osoba z {@code SALES:EDIT}.
 */
@Entity
@Table(name = "region_budgets")
public class RegionBudget {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(name = "region_code", nullable = false, length = 2, updatable = false)
    private String regionCode;

    @Column(name = "margin_percent", precision = 5, scale = 2)
    private BigDecimal marginPercent;

    @Column(name = "max_loss_percent", precision = 5, scale = 2)
    private BigDecimal maxLossPercent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private AppUser updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RegionBudget() {
    }

    public RegionBudget(Store store, String regionCode) {
        this.store = store;
        this.regionCode = regionCode;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    /** Zmienia budżet; zwraca, czy coś się zmieniło (bez zmian nie nadpisujemy autora). */
    public boolean update(BigDecimal marginPercent, BigDecimal maxLossPercent, AppUser by) {
        if (same(this.marginPercent, marginPercent) && same(this.maxLossPercent, maxLossPercent)) {
            return false;
        }
        this.marginPercent = marginPercent;
        this.maxLossPercent = maxLossPercent;
        this.updatedBy = by;
        return true;
    }

    private static boolean same(BigDecimal first, BigDecimal second) {
        return first == null ? second == null : second != null && first.compareTo(second) == 0;
    }

    public UUID getId() {
        return id;
    }

    public String getRegionCode() {
        return regionCode;
    }

    public BigDecimal getMarginPercent() {
        return marginPercent;
    }

    public BigDecimal getMaxLossPercent() {
        return maxLossPercent;
    }

    public AppUser getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
