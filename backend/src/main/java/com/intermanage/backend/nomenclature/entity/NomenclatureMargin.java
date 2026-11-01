package com.intermanage.backend.nomenclature.entity;

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
 * Marża wymagana na rejonie, rodzinie albo podrodzinie nomenklatury sklepu (% od sprzedaży netto). Obowiązuje
 * najbardziej szczegółowa ustawiona; pusta = bez progu na tym poziomie. Opis w V49__nomenclature_margins.sql.
 */
@Entity
@Table(name = "nomenclature_margins")
public class NomenclatureMargin {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(nullable = false, length = 6, updatable = false)
    private String code;

    @Column(name = "margin_percent", precision = 5, scale = 2)
    private BigDecimal marginPercent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private AppUser updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected NomenclatureMargin() {
    }

    public NomenclatureMargin(Store store, String code) {
        this.store = store;
        this.code = code;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    /** Zmienia próg; zwraca, czy coś się zmieniło (bez zmian nie nadpisujemy autora). */
    public boolean update(BigDecimal marginPercent, AppUser by) {
        boolean same = this.marginPercent == null
                ? marginPercent == null
                : marginPercent != null && this.marginPercent.compareTo(marginPercent) == 0;
        if (same) {
            return false;
        }
        this.marginPercent = marginPercent;
        this.updatedBy = by;
        return true;
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public BigDecimal getMarginPercent() {
        return marginPercent;
    }

    public AppUser getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
