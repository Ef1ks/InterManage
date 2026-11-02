package com.intermanage.backend.deliproduction.entity;

import java.time.Instant;
import java.util.UUID;

import com.intermanage.backend.auth.entity.AppUser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Pieczątka zakładu w nagłówku kart produkcji sklepu. */
@Entity
@Table(name = "production_card_settings")
public class ProductionCardSettings {

    @Id
    @Column(name = "store_id")
    private UUID storeId;

    @Column(nullable = false)
    private String stamp;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private AppUser updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProductionCardSettings() {
    }

    public ProductionCardSettings(UUID storeId) {
        this.storeId = storeId;
    }

    public void update(String stamp, AppUser by) {
        this.stamp = stamp;
        this.updatedBy = by;
        this.updatedAt = Instant.now();
    }

    public UUID getStoreId() {
        return storeId;
    }

    public String getStamp() {
        return stamp;
    }
}
