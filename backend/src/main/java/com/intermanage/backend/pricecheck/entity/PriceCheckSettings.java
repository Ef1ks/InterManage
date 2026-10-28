package com.intermanage.backend.pricecheck.entity;

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
 * Ustawienia weryfikacji cen w sklepie: minimalna akceptowana marża (% od sprzedaży netto) towaru z bazy kupionego
 * u innego dostawcy. Pusta = raport niczego nie zgłasza. Ustawia ją osoba z {@code SALES:EDIT}.
 */
@Entity
@Table(name = "price_check_settings")
public class PriceCheckSettings {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(name = "min_margin_percent", precision = 5, scale = 2)
    private BigDecimal minMarginPercent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private AppUser updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PriceCheckSettings() {
    }

    public PriceCheckSettings(Store store) {
        this.store = store;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    /** Zmienia minimalną marżę; zwraca, czy coś się zmieniło (bez zmian nie nadpisujemy autora). */
    public boolean update(BigDecimal minMarginPercent, AppUser by) {
        if (this.minMarginPercent == null ? minMarginPercent == null
                : minMarginPercent != null && this.minMarginPercent.compareTo(minMarginPercent) == 0) {
            return false;
        }
        this.minMarginPercent = minMarginPercent;
        this.updatedBy = by;
        return true;
    }

    public BigDecimal getMinMarginPercent() {
        return minMarginPercent;
    }

    public AppUser getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
