package com.intermanage.backend.cash.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** Stan kasy na początek dnia, od którego sklep prowadzi raport (opis w V136__cash_report.sql). */
@Entity
@Table(name = "cash_opening_balances")
public class CashOpeningBalance {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(name = "balance_date", nullable = false, updatable = false)
    private LocalDate balanceDate;

    @Column(nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "removed_by")
    private AppUser removedBy;

    @Column(name = "removed_at")
    private Instant removedAt;

    protected CashOpeningBalance() {
    }

    public CashOpeningBalance(UUID storeId, LocalDate balanceDate, BigDecimal amount, AppUser createdBy) {
        this.storeId = storeId;
        this.balanceDate = balanceDate;
        this.amount = amount;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public void remove(AppUser by) {
        removedAt = Instant.now();
        removedBy = by;
    }

    public UUID getId() {
        return id;
    }

    public LocalDate getBalanceDate() {
        return balanceDate;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public AppUser getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
