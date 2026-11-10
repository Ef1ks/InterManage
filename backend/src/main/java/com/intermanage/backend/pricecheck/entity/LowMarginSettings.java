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
 * Progi raportu marż poza normą w sklepie (punkty procentowe od marży wymaganej): {@code lowGap} niżej = za niska,
 * {@code highGap} wyżej = za wysoka. Ustawia je osoba z {@code SALES:EDIT}; bez wiersza obowiązują domyślne.
 */
@Entity
@Table(name = "low_margin_settings")
public class LowMarginSettings {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(name = "low_gap", nullable = false, precision = 5, scale = 2)
    private BigDecimal lowGap;

    @Column(name = "high_gap", nullable = false, precision = 5, scale = 2)
    private BigDecimal highGap;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private AppUser updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LowMarginSettings() {
    }

    public LowMarginSettings(Store store) {
        this.store = store;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public void update(BigDecimal lowGap, BigDecimal highGap, AppUser by) {
        this.lowGap = lowGap;
        this.highGap = highGap;
        this.updatedBy = by;
    }

    public BigDecimal getLowGap() {
        return lowGap;
    }

    public BigDecimal getHighGap() {
        return highGap;
    }
}
