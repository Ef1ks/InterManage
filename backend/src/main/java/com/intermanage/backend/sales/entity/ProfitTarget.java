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
import jakarta.persistence.Table;

/**
 * Roczny cel zysku sklepu (opis w V162__store_profit_targets.sql). Zmiana i usunięcie dają poprzedniemu wpisowi
 * znacznik {@code deletedAt}.
 */
@Entity
@Table(name = "store_profit_targets")
public class ProfitTarget {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(name = "target_year", nullable = false, updatable = false)
    private int year;

    @Column(name = "margin_value", precision = 14, scale = 2, updatable = false)
    private BigDecimal marginValue;

    @Column(name = "margin_after_losses_value", precision = 14, scale = 2, updatable = false)
    private BigDecimal marginAfterLossesValue;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by")
    private AppUser deletedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected ProfitTarget() {
    }

    public ProfitTarget(Store store, int year, BigDecimal marginValue, BigDecimal marginAfterLossesValue,
            AppUser by) {
        this.store = store;
        this.year = year;
        this.marginValue = marginValue;
        this.marginAfterLossesValue = marginAfterLossesValue;
        this.createdBy = by;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public void delete(AppUser by) {
        deletedAt = Instant.now();
        deletedBy = by;
    }

    /** Czy wpis ma te same kwoty (bez zmian nie robimy nowego wpisu). */
    public boolean sameAs(BigDecimal marginValue, BigDecimal marginAfterLossesValue) {
        return same(this.marginValue, marginValue) && same(this.marginAfterLossesValue, marginAfterLossesValue);
    }

    private static boolean same(BigDecimal first, BigDecimal second) {
        return first == null ? second == null : second != null && first.compareTo(second) == 0;
    }

    public UUID getId() {
        return id;
    }

    public int getYear() {
        return year;
    }

    public BigDecimal getMarginValue() {
        return marginValue;
    }

    public BigDecimal getMarginAfterLossesValue() {
        return marginAfterLossesValue;
    }

    public AppUser getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
